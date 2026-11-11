package com.delightroom.mission.app.worker.rehydrate

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.infra.redis.RedisKeys
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
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
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * `worker: member` 의 감시(ORACLE platform O22 — LLD platform §4.1 발동 2). 잡 본문 [MemberJob.run] · [MemberJob.watchOnce] 를 직접 부른다.
 * 게임 시각 `D 07:30`. PostgreSQL 의 진행 기록은 persister 대신 테스트가 쓴다(그날 행 · 원장 · 출제 이력).
 */
@SpringBootTest(
    properties = [
        "spring.sql.init.mode=always",
        "worker.persister.enabled=false",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
@DirtiesContext
class RehydrateWatchTest
    @Autowired
    constructor(
        private val job: MemberJob,
        private val rosters: RosterRepository,
        private val gameClock: GameClock,
        private val keys: RedisKeys,
        redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
    ) {
        private val fx = RehydrateFixture(db, redis, keys, D)

        private fun at(time: String) = OffsetDateTime.parse("${D}T$time+09:00")

        @Test
        fun `O22 표시가 다 있으면 그대로 · FLUSHALL 로 사라지면 다시 올리고 · 본 적 없는 날짜는 돌지 않는다`(): Unit = runBlocking {
            val g = fx.group(3)
            val (u1, u2, u3) = g.uids
            val after = GroupId(g.gid.value - 1)

            val first = job.run(D, startAfter = after)
            assertThat(first.failedPages).isZero()
            assertThat(first.rehydrated).isZero()
            assertThat(fx.group(g)).containsEntry("version", "0").containsEntry("$u1:p", "-1")
            assertThat(rosters.countUnmarked(D)).isZero()
            assertThat(keys.bucketCount).isEqualTo(1_024)
            assertThat(job.watchOnce(D, after)).isFalse()

            // 게임이 진행돼 persister 가 쓴 기록 — u1: 1번 풀이 · 2번 진입, u2: 시작 · 1번 진입, u3: 시작 안 함
            val q1 = fx.questions(1)
            val q2 = fx.questions(2)
            progressRow(u1, 1, "IN_PROGRESS", at("07:10:00.000001"), at("07:12:00.000002"))
            progressRow(u2, 0, "IN_PROGRESS", at("07:11:00.000003"), null)
            sql(
                "INSERT INTO solve_signals (game_date, user_id, question_no, question_id, success_time, click_time, arrived_at) " +
                    "VALUES ('$D', $u1, 1, ${q1[0].id}, '${at("07:12:00")}', '${at("07:12:00")}', '${at("07:12:00.000002")}')",
            )
            shownRow(u1, q1[0].id, 1, "SOLVED")
            shownRow(u1, q2[0].id, 2, "SHOWN")
            shownRow(u2, q1[0].id, 1, "SHOWN")

            fx.flushAll()
            val from = gameClock.now().toEpochMilli()
            assertThat(job.watchOnce(D, after)).isTrue()
            val to = gameClock.now().toEpochMilli()

            val hash = fx.group(g)
            val us = { t: OffsetDateTime -> (t.toInstant().epochSecond * 1_000_000 + t.toInstant().nano / 1_000).toString() }
            assertThat(hash).containsEntry("$u1:p", "1").containsEntry("$u1:st", "IN_PROGRESS")
                .containsEntry("$u1:sa", us(at("07:10:00.000001"))).containsEntry("$u1:late", "1")
                .containsEntry("$u1:ls", us(at("07:12:00.000002"))).containsEntry("$u1:cur", q2[0].pk)
                .containsEntry("$u2:p", "0").containsEntry("$u2:cur", q1[0].pk).doesNotContainKey("$u2:ls")
                .containsEntry("$u3:p", "-1").containsEntry("$u3:st", "NOT_STARTED").doesNotContainKey("$u3:sa")
                .doesNotContainKeys("result", "ranking", "pending")
            assertThat(hash.getValue("version").toLong()).isBetween(from, to)
            assertThat(fx.isDayMember(g)).isTrue()
            assertThat(rosters.countUnmarked(D)).isZero()
            assertThat(job.watchOnce(D, after)).isFalse()

            // 표시를 본 적 없는 날짜 — 표시가 없어도 돌지 않는다
            val other = D.plusDays(1)
            assertThat(rosters.countUnmarked(other)).isEqualTo(1_024)
            assertThat(job.watchOnce(other, after)).isFalse()
            assertThat(hasRoster(other, g)).isFalse()
        }

        private suspend fun hasRoster(day: LocalDate, g: TestGroup): Boolean = fx.hash(keys.members(g.gid, day)).isNotEmpty()

        private suspend fun progressRow(uid: Long, p: Int, st: String, startedAt: OffsetDateTime, lastSolvedAt: OffsetDateTime?) {
            var spec = db.sql(
                "UPDATE daily_progress SET progress = :p, status = :st, started_at = :sa, is_late = true, " +
                    "last_solved_at = :ls WHERE game_date = :d AND user_id = :u",
            ).bind("p", p).bind("st", st).bind("sa", startedAt).bind("d", D).bind("u", uid)
            spec = if (lastSolvedAt == null) spec.bindNull("ls", OffsetDateTime::class.java) else spec.bind("ls", lastSolvedAt)
            spec.then().awaitFirstOrNull()
        }

        private suspend fun shownRow(uid: Long, qid: Long, no: Int, kind: String) =
            sql(
                "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                    "VALUES ($uid, $qid, '$D', $no, '$kind', now())",
            )

        private suspend fun sql(statement: String) {
            db.sql(statement).then().awaitFirstOrNull()
        }

        companion object {
            val D: LocalDate = LocalDate.now(RehydrateFixture.ZONE).plusDays(11)

            @JvmStatic
            @DynamicPropertySource
            fun clockOffset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { RehydrateFixture.offsetTo(D, LocalTime.of(7, 30)) }
            }
        }
    }
