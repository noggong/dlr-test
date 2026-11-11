package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.app.worker.member.MemberProperties
import com.delightroom.mission.app.worker.rehydrate.RehydrateFixture
import com.delightroom.mission.app.worker.rehydrate.TestGroup
import com.delightroom.mission.core.config.DemoClockProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.DemoClockRules
import com.delightroom.mission.core.domain.game.DemoJob
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.DemoClockStore
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.DeadlineService
import com.delightroom.mission.core.service.DemoClockService
import com.delightroom.mission.core.service.DemoJobAck
import com.delightroom.mission.infra.redis.RedisKeys
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections

/**
 * 경계를 넘는 시각 이동(ORACLE platform O47 — prd D26 · LLD platform §4.4-3 · 5). 실제 `worker: member` · `deadline` 잡을 띄우고
 * 같은 `GameProperties` 위의 [DemoClockService] 로 옮긴다 — 앱 둘(api · worker)이 같은 이동량을 쓰는 꼴이다(sync 는 O42 가 본다).
 * 게임 시각은 `D 06:00` 에서 시작한다(`game.clock-offset`). 잡은 DB 의 모든 그룹을 돌므로 판정은 이 테스트가 만든 그룹으로 좁힌다.
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
@ContextConfiguration(initializers = [WorkerTestContainers.FreshRedisInitializer::class])
@DirtiesContext
class DemoClockMoveTest
    @Autowired
    constructor(
        private val game: GameProperties,
        private val gameClock: GameClock,
        private val store: DemoClockStore,
        private val groups: GroupRepository,
        private val users: UserRepository,
        private val rosters: RosterRepository,
        private val dailyProgress: DailyProgressStore,
        private val groupDays: GroupDayRepository,
        private val deadlines: DeadlineService,
        private val keys: RedisKeys,
        private val redis: ReactiveStringRedisTemplate,
        db: DatabaseClient,
    ) {
        private val fx = RehydrateFixture(db, redis, keys, D)

        /** 표시를 쓴 순서 — (필드, 날짜, 그때 게임 시각). */
        private data class Ack(val field: String, val date: LocalDate, val gameNow: Instant)

        private fun kst(date: LocalDate, time: String): Instant = date.atTime(LocalTime.parse(time)).atZone(game.zone).toInstant()

        @Test
        fun `O47 경계를 넘는 이동이 건너뛴 경계의 일을 차례로 한 번씩 돌게 한다 — 06시에서 08시 30분 · 게임 중 to start · 사흘 건너뛰기`(): Unit =
            runBlocking {
                // 이동량 정본 0 — 서비스가 첫 이동량을 새로 정하지 않게
                redis.opsForValue().set(keys.demoClock(), "0").awaitSingleOrNull()
                val demo = DemoClockProperties(syncInterval = Duration.ofMillis(200), jobWait = Duration.ofSeconds(90))
                val service = DemoClockService(store, game, demo, Clock.system(game.zone)).also { it.start() }
                val acks = Collections.synchronizedList(mutableListOf<Ack>())
                val ack = DemoJobAck { field, date ->
                    acks += Ack(field, date, gameClock.now())
                    service.markDone(field, date)
                }
                val member = MemberJob(groups, users, rosters, dailyProgress, groupDays, gameClock, MemberProperties(), ack)
                val deadline = DeadlineScheduler(deadlines, gameClock, game, DeadlineProperties(), ack)
                val g = fx.group(2)
                member.start()
                deadline.start()
                try {
                    assertThat(gameClock.playDate(gameClock.now())).isEqualTo(D)

                    // ⓐ 06:00 → 08:30 — 07:00 명단 · 08:00 · 08:00:03 을 차례로
                    service.move(DemoClockService.Target.At(kst(D, "08:30:00")))
                    assertThat(gameClock.now()).isBetween(kst(D, "08:30:00"), kst(D, "08:30:30"))
                    assertDay(g, D)
                    assertThat(acks.filter { it.date == D }.map { it.field }).containsExactly(
                        DemoJob.MEMBER.field(), DemoJob.DEADLINE_FIRST.field(), DemoJob.DEADLINE_SECOND.field(),
                    )

                    // ⓑ 다음 날 07:30(07:00 을 넘음) → 게임 중간에서 to start — 그날 08:00 · 08:00:03 을 돌리고 다음 날 06:59:50
                    val d1 = D.plusDays(1)
                    service.move(DemoClockService.Target.Preset(DemoClockRules.Preset.START))
                    assertThat(gameClock.now()).isBetween(kst(d1, "06:59:50"), kst(d1, "07:00:00"))
                    service.move(DemoClockService.Target.At(kst(d1, "07:30:00")))
                    val moved = service.move(DemoClockService.Target.Preset(DemoClockRules.Preset.START))
                    assertThat(moved.gameNow).isBetween(kst(d1.plusDays(1), "06:59:50"), kst(d1.plusDays(1), "07:00:00"))
                    assertDay(g, d1)

                    // ⓒ 사흘을 건너뛴다 — D+2 · D+3 · D+4 의 경계 9개
                    val last = D.plusDays(4)
                    service.move(DemoClockService.Target.At(kst(last, "08:30:00")))
                    (2L..4L).forEach { assertDay(g, D.plusDays(it)) }

                    // 날마다 명단 → 1차 → 2차 한 번씩, 경계 시각 뒤에
                    for (day in (0L..4L).map { D.plusDays(it) }) {
                        val mine = acks.filter { it.date == day }
                        assertThat(mine.map { it.field }).`as`("$day").containsExactly(
                            DemoJob.MEMBER.field(), DemoJob.DEADLINE_FIRST.field(), DemoJob.DEADLINE_SECOND.field(),
                        )
                        assertThat(mine[0].gameNow).isAfterOrEqualTo(kst(day, "07:00:00")).isBefore(kst(day, "08:00:00"))
                        assertThat(mine[1].gameNow).isAfterOrEqualTo(kst(day, "08:00:00"))
                        assertThat(mine[2].gameNow).isAfterOrEqualTo(kst(day, "08:00:03"))
                    }
                    assertThat(store.doneDates()).containsEntry(DemoJob.MEMBER.field(), last)
                        .containsEntry(DemoJob.DEADLINE_FIRST.field(), last).containsEntry(DemoJob.DEADLINE_SECOND.field(), last)
                } finally {
                    member.stop()
                    deadline.stop()
                    service.close()
                    redis.delete(keys.demoClock(), keys.demoJobs()).awaitSingleOrNull()
                    game.shift.set(0)
                }
            }

        /** [day] 의 명단과 그룹 결과 · 랭킹 확정이 Redis 에 있다. */
        private suspend fun assertDay(g: TestGroup, day: LocalDate) {
            assertThat(fx.hash(keys.members(g.gid, day))).`as`("$day 명단").isNotEmpty
            assertThat(fx.hash(keys.group(g.gid, day))).`as`("$day 그룹").containsKey("result").containsEntry("rankingFinal", "1")
        }

        companion object {
            val D: LocalDate = LocalDate.now(RehydrateFixture.ZONE).plusDays(40)

            @JvmStatic
            @DynamicPropertySource
            fun clockOffset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { RehydrateFixture.offsetTo(D, LocalTime.of(6, 0)) }
            }
        }
    }
