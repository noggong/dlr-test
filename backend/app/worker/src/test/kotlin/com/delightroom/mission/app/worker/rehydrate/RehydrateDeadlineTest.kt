package com.delightroom.mission.app.worker.rehydrate

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupDayRecord
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
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
 * 마감 뒤 재적재 — 마감 따라잡기(ORACLE platform O26 — LLD platform §4.2 발동 3).
 * 게임 시각 `D 08:00:10`(08:00:03 뒤 — Lua `deadline` 이 확정까지 한다). Redis 는 비어 있고 PostgreSQL 에 그날 기록만 있다.
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
class RehydrateDeadlineTest
    @Autowired
    constructor(
        private val job: MemberJob,
        private val gameClock: GameClock,
        private val rosters: RosterRepository,
        private val dailyProgress: DailyProgressStore,
        keys: RedisKeys,
        redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
    ) {
        private val fx = RehydrateFixture(db, redis, keys, D)

        private fun at(time: String) = "'${D}T$time+09:00'"

        private fun us(time: String): String {
            val t = OffsetDateTime.parse("${D}T$time+09:00").toInstant()
            return (t.epochSecond * 1_000_000 + t.nano / 1_000).toString()
        }

        @Test
        fun `O26 08시 뒤 재적재한 그룹은 마감을 따라잡는다 — 결과 없음은 확정 · 마감 확정은 랭킹 확정 · 조기 확정은 그대로`(): Unit = runBlocking {
            assertThat(gameClock.now()).isAfter(gameClock.deadlineAt(D).plusSeconds(3))
            val c = fx.group(3)
            val s = fx.group(2)
            val t = fx.group(2)
            val (c1, c2, c3) = c.uids
            // C: 진행도 2 · 1 · 시작 안 함, 결과 없음
            row(c, c1, 2, "IN_PROGRESS", "07:10:00", "07:30:00.000001")
            row(c, c2, 1, "IN_PROGRESS", "07:11:00", "07:20:00.000002")
            row(c, c3, -1, "NOT_STARTED", null, null)
            // S: 마감 확정(08:00:00.5 — FAILED_DEADLINE) · 랭킹 확정은 아직
            row(s, s.uids[0], 3, "SUCCEEDED", "07:10:00", "07:40:00")
            row(s, s.uids[1], 1, "FAILED", "07:10:00", "07:15:00")
            result(s, "FAILED_DEADLINE", "08:00:00.5")
            // T: 08:00 전 조기 확정(SUCCEEDED) — 랭킹은 그 자리에서 확정이었다
            row(t, t.uids[0], 3, "SUCCEEDED", "07:10:00", "07:39:00")
            row(t, t.uids[1], 3, "SUCCEEDED", "07:10:00", "07:40:00.000003")
            result(t, "SUCCEEDED", "07:40:00.000003")

            // C 는 앞 실행이 명단을 세우고 따라잡기 전에 끊긴 것처럼 먼저 세운다 — 이번 실행이 만들지 않아도 따라잡아야 한다
            val members = c.uids.associate { UserId(it) to LoginId("c$it") }
            val cRoster = DayRoster(c.gid, D, GroupName("c"), members)
            val record: GroupDayRecord? = dailyProgress.loadDayStates(D, listOf(c.gid))[c.gid]
            assertThat(rosters.createIfAbsent(cRoster, GroupRestore.of(cRoster, record, gameClock.now(), gameClock.deadlineAt(D)))).isTrue()
            assertThat(fx.group(c)).doesNotContainKey("result")

            val first = job.run(D, startAfter = GroupId(c.gid.value - 1))

            assertThat(first.rehydrated).isEqualTo(2)
            assertThat(first.caughtUp).isEqualTo(2)
            assertThat(first.complete).isTrue()
            val hc = fx.group(c)
            assertThat(hc).containsEntry("result", "FAILED_DEADLINE").containsEntry("rankingFinal", "1")
                .containsEntry("$c1:st", "FAILED").containsEntry("$c2:st", "FAILED").containsEntry("$c3:st", "FAILED")
                .containsEntry("$c1:p", "2").doesNotContainKey("pending")
            assertThat(fx.events(c).map { it["type"] to it["rankingFinal"] }).containsExactly("group.decided" to "1")

            val hs = fx.group(s)
            assertThat(hs).containsEntry("result", "FAILED_DEADLINE").containsEntry("decidedAt", us("08:00:00.5"))
                .containsEntry("rankingFinal", "1").containsEntry("${s.uids[1]}:st", "FAILED")
            assertThat(fx.events(s).map { it["type"] }).containsExactly("group.ranking_final")

            val ht = fx.group(t)
            assertThat(ht).containsEntry("result", "SUCCEEDED").containsEntry("decidedAt", us("07:40:00.000003"))
                .containsEntry("rankingFinal", "1").containsKey("ranking")
            assertThat(fx.events(t)).isEmpty()

            // 두 번째 실행 — 명단이 있으니 아무것도 바꾸지 않는다
            val second = job.run(D, startAfter = GroupId(c.gid.value - 1))
            assertThat(second.created).isZero()
            assertThat(second.caughtUp).isZero()
            assertThat(fx.group(c)).isEqualTo(hc)
            assertThat(fx.group(s)).isEqualTo(hs)
            assertThat(fx.group(t)).isEqualTo(ht)
            assertThat(fx.events(c)).hasSize(1)
            assertThat(fx.events(s)).hasSize(1)
            assertThat(fx.events(t)).isEmpty()
        }

        private suspend fun row(g: TestGroup, uid: Long, p: Int, st: String, startedAt: String?, lastSolvedAt: String?) {
            val sa = startedAt?.let { at(it) } ?: "NULL"
            val late = if (startedAt == null) "NULL" else "false"
            val ls = lastSolvedAt?.let { at(it) } ?: "NULL"
            sql(
                "INSERT INTO daily_progress (game_date, user_id, group_id, progress, status, started_at, is_late, last_solved_at) " +
                    "VALUES ('$D', $uid, ${g.gid.value}, $p, '$st', $sa, $late, $ls)",
            )
        }

        private suspend fun result(g: TestGroup, result: String, completedAt: String) =
            sql(
                "INSERT INTO group_results (game_date, group_id, result, completed_at, member_count) " +
                    "VALUES ('$D', ${g.gid.value}, '$result', ${at(completedAt)}, ${g.uids.size})",
            )

        private suspend fun sql(statement: String) {
            db.sql(statement).then().awaitFirstOrNull()
        }

        companion object {
            val D: LocalDate = LocalDate.now(RehydrateFixture.ZONE).plusDays(12)

            @JvmStatic
            @DynamicPropertySource
            fun clockOffset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { RehydrateFixture.offsetTo(D, LocalTime.of(8, 0, 10)) }
            }
        }
    }
