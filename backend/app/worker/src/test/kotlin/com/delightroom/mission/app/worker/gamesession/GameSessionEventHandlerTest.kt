package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.gamesession.GameSessionEventFixture.Companion.startUs
import com.delightroom.mission.app.worker.gamesession.GameSessionEventFixture.Companion.usToInstant
import com.delightroom.mission.app.worker.persister.GameSessionEventHandler
import com.delightroom.mission.app.worker.persister.StreamPersister
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.service.RosterService
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * game-session 의 persister 처리기(ORACLE game-session O25 · O26 — LLD §3.7 · §6 Q11 · Q12 · HLD §4.1).
 *
 * O26 은 처리기를 직접 두 번 부른다(칸은 Lua `start` · `shown` 이 내는 모양). O25 는 실제 Lua(`ProgressRepository`)로 이벤트를 내고
 * 스프링이 띄운 소비 루프가 처리하게 둔다 — 처리기가 루프에 걸려 있고 버킷이 서지 않는다는 것도 함께 본다.
 * 시각은 `game.clock-offset` 으로 [D] 07:05 — Lua 창 안이다. I7 은 Stream 을 세야 해서 루프를 끈 [GameSessionEventInvariantTest].
 */
@SpringBootTest(
    properties = [
        "game.bucket-count=8",
        "spring.sql.init.mode=always",
        "worker.persister.idle-delay=20ms",
        "worker.persister.retry-backoff=100ms",
        "worker.persister.retry-backoff-max=500ms",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.FreshRedisInitializer::class])
@DirtiesContext
@ExtendWith(OutputCaptureExtension::class)
class GameSessionEventHandlerTest
    @Autowired
    constructor(
        private val handler: GameSessionEventHandler,
        private val persister: StreamPersister,
        private val progresses: ProgressRepository,
        private val rosters: RosterService,
        private val dailyProgress: DailyProgressStore,
        private val db: DatabaseClient,
        private val redis: ReactiveStringRedisTemplate,
    ) {
        private val fx = GameSessionEventFixture(db, redis, bucketCount = 8, date = D)

        @BeforeEach
        fun running(): Unit = runBlocking {
            assertThat(persister.isRunning).isTrue()
            dailyProgress.ensurePartitions(D)
        }

        private suspend fun pending(g: GameSessionEventFixture.TestGroup): Long =
            redis.opsForStream<String, String>().pending(fx.stream(g), "persister").awaitSingle().totalPendingMessages

        /** 소비 루프가 그룹 Stream 의 마지막 메시지까지 읽고 ACK 할 때까지 기다린다. */
        private suspend fun drained(g: GameSessionEventFixture.TestGroup) {
            val stream = fx.stream(g)
            val last = redis.opsForStream<String, String>().reverseRange(stream, Range.unbounded<String>()).next()
                .awaitFirstOrNull()?.id?.value ?: return
            withTimeout(10_000) {
                while (true) {
                    val group = redis.opsForStream<String, String>().groups(stream).collectList().awaitSingle()
                        .single { it.groupName() == "persister" }
                    if (idAtLeast(group.lastDeliveredId(), last) && pending(g) == 0L) break
                    delay(20)
                }
            }
        }

        private fun idAtLeast(id: String, other: String): Boolean {
            val (a, b) = id.split("-").map { it.toLong() }
            val (c, d) = other.split("-").map { it.toLong() }
            return a > c || (a == c && b >= d)
        }

        private fun lines(output: CapturedOutput, login: String): List<String> =
            output.all.lines().filter { "question.served_duplicate" in it && "\"$login\"" in it }

        @Test
        fun `O26 mission_started 와 question_shown 을 두 번 처리해도 행 1개 · 운영 로그 0줄`(output: CapturedOutput): Unit = runBlocking {
            val g = fx.group(2)
            val (a, b) = g.uids
            // a 는 worker member 의 -1 행이 있고(갱신 경로), b 는 행이 없다(삽입 경로)
            dailyProgress.insertNotStarted(D, listOf(UserId(a) to GroupId(g.gid)))
            val q1 = fx.question(1)
            val startA = startUs(D, 0, 500_000)
            val startB = startUs(D, 5, 0)
            val events = listOf(
                fx.started(g, a, startA, 0), fx.started(g, b, startB, 1),
                fx.shown(g, a, 1, q1, startA + 1_000), fx.shown(g, b, 1, q1, startB + 1_000),
            )
            for (fields in events) handler.handle(StreamEvent("0-1", fields))
            val firstProgress = fx.progressRows(g.uids)
            val firstServed = fx.servedRows(g.uids)
            for (fields in events) handler.handle(StreamEvent("0-2", fields))

            // 재처리는 updated_at 만 바꾼다(Q11 은 충돌 때도 updated_at = now())
            val progress = fx.progressRows(g.uids)
            assertThat(progress).hasSize(2)
            assertThat(progress.map { it - "updated_at" }).isEqualTo(firstProgress.map { it - "updated_at" })
            for ((uid, us, late) in listOf(Triple(a, startA, false), Triple(b, startB, true))) {
                val row = progress.single { (it["user_id"] as Number).toLong() == uid }
                assertThat((row["progress"] as Number).toInt()).isZero()
                assertThat(row["status"]).isEqualTo("IN_PROGRESS")
                assertThat((row["started_at"] as OffsetDateTime).toInstant()).isEqualTo(usToInstant(us))
                assertThat(row["is_late"]).isEqualTo(late)
                assertThat((row["group_id"] as Number).toLong()).isEqualTo(g.gid)
            }
            val served = fx.servedRows(g.uids)
            assertThat(served).isEqualTo(firstServed).hasSize(2)
            for (row in served) {
                assertThat(row["kind"]).isEqualTo("SHOWN")
                assertThat(row["game_date"]).isEqualTo(D)
                assertThat((row["question_no"] as Number).toInt()).isEqualTo(1)
                assertThat((row["question_id"] as Number).toLong()).isEqualTo(q1.id)
            }
            g.logins.values.forEach { assertThat(lines(output, it)).describedAs("운영 로그 $it").isEmpty() }
        }

        @Test
        fun `O26 (바탕) 늦게 온 mission_started 는 진행도 · 끝난 상태 · 처음 시작 시각을 되돌리지 않는다`(): Unit = runBlocking {
            val g = fx.group(2)
            val uid = g.uids[0]
            handler.handle(StreamEvent("0-1", fx.started(g, uid, startUs(D, 0, 100_000), 0)))
            db.sql("UPDATE daily_progress SET progress = 3, status = 'SUCCEEDED' WHERE game_date = :d AND user_id = :u")
                .bind("d", D).bind("u", uid).then().awaitFirstOrNull()
            handler.handle(StreamEvent("0-2", fx.started(g, uid, startUs(D, 9, 0), 1)))

            val row = fx.progressRows(listOf(uid)).single()
            assertThat((row["progress"] as Number).toInt()).isEqualTo(3)
            assertThat(row["status"]).isEqualTo("SUCCEEDED")
            assertThat((row["started_at"] as OffsetDateTime).toInstant()).isEqualTo(usToInstant(startUs(D, 0, 100_000)))
            assertThat(row["is_late"]).isEqualTo(false)
        }

        @Test
        fun `O25 이미 낸 문제의 진입 신호는 거부하지 않고 운영 로그 WARN JSON 정확히 1줄 · 기존 행 그대로`(output: CapturedOutput): Unit = runBlocking {
            val g = fx.group(2)
            val alice = g.uids[0]
            val login = g.logins.getValue(alice)
            val q = fx.question(1, offset = 1)
            db.sql(
                "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                    "VALUES (:u, :q, :d, 2, 'SOLVED', now())",
            ).bind("u", alice).bind("q", q.id).bind("d", D.minusDays(1)).then().awaitFirstOrNull()
            rosters.findOrCreate(GroupId(g.gid), D)

            assertThat(progresses.startMission(UserId(alice), GroupId(g.gid), D)).isInstanceOf(MissionStartOutcome.Started::class.java)
            val reply = progresses.markShown(UserId(alice), GroupId(g.gid), D, 1, QuestionId(q.pk), q.id)
            assertThat(reply).describedAs("진입 200").isEqualTo(ShownOutcome.Shown(0, MemberStatus.IN_PROGRESS, true))
            drained(g)

            val expected = """{"event":"question.served_duplicate","date":"$D","loginId":"$login","questionNo":1,"questionId":"${q.pk}"}"""
            val logged = lines(output, login)
            assertThat(logged).describedAs("운영 로그").hasSize(1)
            assertThat(logged.single()).contains("WARN").endsWith(expected)
            val row = fx.servedRows(listOf(alice)).single()
            assertThat(row["game_date"]).isEqualTo(D.minusDays(1))
            assertThat((row["question_no"] as Number).toInt()).isEqualTo(2)
            assertThat(row["kind"]).isEqualTo("SOLVED")
            // 같은 버킷의 mission.started 도 반영됐다 — 출제 중복이 버킷을 세우지 않는다
            assertThat(fx.progressRows(listOf(alice)).single()["status"]).isEqualTo("IN_PROGRESS")
        }

        companion object {
            /** 다른 테스트의 날짜와 겹치지 않는 날 — 기동 때의 오늘 · 내일 파티션, member 테스트(+7) · 부하 테스트(+10)와 다르다. */
            private val D: LocalDate = LocalDate.now(GameSessionEventFixture.KST).plusDays(13)

            @JvmStatic
            @DynamicPropertySource
            fun offset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { GameSessionEventFixture.offsetTo0705(D) }
            }
        }
    }
