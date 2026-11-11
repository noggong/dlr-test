package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.gamesession.GameSessionEventFixture.Companion.usToInstant
import com.delightroom.mission.app.worker.persister.GameSessionEventHandler
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.service.RosterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 반영과 이벤트는 함께(ORACLE game-session I7 — LLD §3.7 · §4.2 · §4.3). 실제 Lua(`ProgressRepository`)를 동시에 여러 번 부른 뒤
 * 그룹 Hash 의 `<uid>:sa` · `<uid>:cur` 수와 Stream 의 `mission.started` · `question.shown` 수를 전수 비교하고, 그 이벤트를
 * 차례대로 처리기에 넘겨 `daily_progress` · `served_questions` 행 수도 같은지 본다.
 *
 * persister 는 ACK 한 메시지를 트림하므로 이벤트를 세려면 소비 루프를 꺼야 한다(`worker.persister.enabled=false`) — 루프 대신
 * 이 테스트가 Stream 순서대로 처리기를 부른다. 시각은 `game.clock-offset` 으로 [D] 07:05.
 */
@SpringBootTest(
    properties = [
        "game.bucket-count=8",
        "spring.sql.init.mode=always",
        "worker.persister.enabled=false",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.FreshRedisInitializer::class])
@DirtiesContext
class GameSessionEventInvariantTest
    @Autowired
    constructor(
        private val handler: GameSessionEventHandler,
        private val progresses: ProgressRepository,
        private val rosters: RosterService,
        private val dailyProgress: DailyProgressStore,
        private val properties: GameProperties,
        db: DatabaseClient,
        private val redis: ReactiveStringRedisTemplate,
    ) {
        private val fx = GameSessionEventFixture(db, redis, bucketCount = 8, date = D)

        @Test
        fun `I7 sa 칸이 있는 그룹원 수 = mission_started 수 · cur 고정 수 = question_shown 수 · persister 행 수도 같다`(): Unit = runBlocking {
            dailyProgress.ensurePartitions(D)
            val g = fx.group(6)
            val gid = GroupId(g.gid)
            rosters.findOrCreate(gid, D)
            val starters = g.uids.take(4)
            val viewers = starters.take(3)
            val q = listOf(fx.question(1, offset = 2), fx.question(1, offset = 3))
            withContext(Dispatchers.Default) {
                // 같은 유저 문제은행 20건 동시 — 시작은 한 번
                starters.flatMap { uid -> (1..20).map { async { progresses.startMission(UserId(uid), gid, D) } } }.awaitAll()
                // 같은 번호 · 다른 PK 2개를 10건씩(20건) 동시 — 지금 문제는 하나
                viewers.flatMap { uid ->
                    (1..20).map { i ->
                        val pick = q[i % 2]
                        async { progresses.markShown(UserId(uid), gid, D, 1, QuestionId(pick.pk), pick.id) }
                    }
                }.awaitAll()
            }

            val hash = redis.opsForHash<String, String>()
                .entries("g:{${properties.bucketOf(g.gid)}}:${g.gid}:$D").collectList().awaitSingle()
                .associate { it.key to it.value }
            val events = fx.events(g)
            val started = events.map { it.value }.filter { it["type"] == "mission.started" }
            val shown = events.map { it.value }.filter { it["type"] == "question.shown" }
            val sa = g.uids.filter { "$it:sa" in hash }
            val cur = g.uids.filter { "$it:cur" in hash }
            assertThat(sa).containsExactlyInAnyOrderElementsOf(starters)
            assertThat(cur).containsExactlyInAnyOrderElementsOf(viewers)
            assertThat(started.map { it.getValue("uid").toLong() }).describedAs("mission.started").containsExactlyInAnyOrderElementsOf(sa)
            assertThat(shown.map { it.getValue("uid").toLong() to it.getValue("questionId") }).describedAs("question.shown")
                .containsExactlyInAnyOrderElementsOf(cur.map { it to hash.getValue("$it:cur") })
            for (e in started) {
                assertThat(e.getValue("startedAt")).isEqualTo(hash.getValue("${e.getValue("uid")}:sa"))
            }

            // persister 몫 — Stream 순서대로, 두 번씩(재전달)
            repeat(2) { for (e in events) handler.handle(StreamEvent(e.id.value, e.value)) }
            val progress = fx.progressRows(g.uids)
            assertThat(progress).hasSize(g.uids.size)
            val begun = progress.filter { it["started_at"] != null }
            assertThat(begun.map { (it["user_id"] as Number).toLong() }).containsExactlyInAnyOrderElementsOf(sa)
            for (row in begun) {
                assertThat(row["status"]).isEqualTo("IN_PROGRESS")
                assertThat((row["started_at"] as OffsetDateTime).toInstant())
                    .isEqualTo(usToInstant(hash.getValue("${row["user_id"]}:sa").toLong()))
            }
            assertThat(progress.filter { it["started_at"] == null }.map { it["status"] }).containsOnly("NOT_STARTED")
            val served = fx.servedRows(g.uids)
            assertThat(served).hasSize(shown.size)
            assertThat(served.map { it["kind"] }).containsOnly("SHOWN")
            assertThat(served.map { (it["user_id"] as Number).toLong() to (it["question_id"] as Number).toLong() })
                .containsExactlyInAnyOrderElementsOf(shown.map { it.getValue("uid").toLong() to it.getValue("qid").toLong() })
        }

        companion object {
            /** 다른 테스트의 날짜와 겹치지 않는 날 — [GameSessionEventHandlerTest](+13)와도 다르다. */
            private val D: LocalDate = LocalDate.now(GameSessionEventFixture.KST).plusDays(14)

            @JvmStatic
            @DynamicPropertySource
            fun offset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { GameSessionEventFixture.offsetTo0705(D) }
            }
        }
    }
