package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineOutcome
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.service.DeadlineService
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.ContextConfiguration
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * `DeadlineScheduler`(LLD deadline-result §3.2 1 · 7 · 8) — 08:00:00 에 1차 · 08:00:03 에 2차를 띄우고,
 * 기동 때 08:00 이 지났으면 그날을 다시 돈다. 시각은 `game.clock-offset` 으로 옮긴다(08:00 을 기다리지 않는다).
 */
@SpringBootTest(properties = ["worker.persister.enabled=false", "worker.deadline.enabled=false"])
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class DeadlineSchedulerTest
    @Autowired
    constructor(
        private val redis: ReactiveStringRedisTemplate,
    ) {
        private val game = GameProperties()
        private val day: LocalDate = LocalDate.of(2031, 1, 10)

        private fun at(time: String, date: LocalDate = day) =
            LocalDateTime.of(date, LocalTime.parse(time)).atZone(game.zone).toInstant()

        /** 시각 계산만 보는 스케줄러 — 저장소는 부르지 않는다. */
        private val pure = DeadlineScheduler(
            DeadlineService(Unused, game),
            GameClock(game, MutableClock(at("00:00"), game.zone)),
            game,
            DeadlineProperties(),
        )

        @Test
        fun `기동 때 다시 돌 날짜는 08시가 지난 가장 늦은 날이고 다음 날 07시 전까지만이다`() {
            assertThat(pure.catchUpDate(at("08:00:00"))).isEqualTo(day)
            assertThat(pure.catchUpDate(at("08:00:05"))).isEqualTo(day)
            assertThat(pure.catchUpDate(at("23:59:59"))).isEqualTo(day)
            assertThat(pure.catchUpDate(at("03:00:00"))).isEqualTo(day.minusDays(1))
            assertThat(pure.catchUpDate(at("06:59:59.999999"))).isEqualTo(day.minusDays(1))
            assertThat(pure.catchUpDate(at("07:00:00"))).isNull()
            assertThat(pure.catchUpDate(at("07:59:59.999999"))).isNull()

            assertThat(pure.nextDate(at("07:59:59.999999"))).isEqualTo(day)
            assertThat(pure.nextDate(at("03:00:00"))).isEqualTo(day)
            assertThat(pure.nextDate(at("08:00:00"))).isEqualTo(day.plusDays(1))
        }

        @Test
        fun `08시 전에 기동하면 08시에 1차 · 08시 3초에 2차를 띄운다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val (decidedGid, pendingGid) = write(f)
            // 지금 = 07:59:58.5 — 1.5초 뒤 1차, 4.5초 뒤 2차
            val game = f.at(Duration.ofMillis(-1_500))
            val scheduler = scheduler(f, game)
            scheduler.start()
            try {
                awaitFinal(f, listOf(decidedGid, pendingGid))
            } finally {
                scheduler.stop()
            }
            val deadlineUs = f.micros(game.deadlineAt(f.date))
            val graceEndUs = f.micros(game.graceEndAt(f.date))
            val events = f.events()

            // 확정 그룹: 1차(08:00 ~ 08:00:03)가 잠정 확정, 2차(08:00:03 뒤)가 랭킹 확정
            val d = events.getValue(decidedGid.value)
            assertThat(d.map { it["type"] }).containsExactly("group.decided", "group.ranking_final")
            assertThat(d[0]["rankingFinal"]).isEqualTo("0")
            assertThat(d[0].getValue("at").toLong()).isBetween(deadlineUs, graceEndUs - 1)
            assertThat(d[1].getValue("at").toLong()).isGreaterThanOrEqualTo(graceEndUs)

            // 미룬 그룹: 1차는 Stream 에 남기지 않고(pending), 2차가 한 번에 확정
            val p = events.getValue(pendingGid.value)
            assertThat(p.map { it["type"] }).containsExactly("group.decided")
            assertThat(p[0]["rankingFinal"]).isEqualTo("1")
            assertThat(p[0].getValue("at").toLong()).isGreaterThanOrEqualTo(graceEndUs)
        }

        @Test
        fun `08시 3초 뒤에 기동하면 그날 1차 · 2차를 곧바로 다시 돈다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val (decidedGid, pendingGid) = write(f)
            // 그날 08:00:05 — 재기동 · 스케줄 놓침
            val game = f.at(Duration.ofSeconds(5))
            val scheduler = scheduler(f, game)
            scheduler.start()
            try {
                awaitFinal(f, listOf(decidedGid, pendingGid))
            } finally {
                scheduler.stop()
            }
            val events = f.events()
            // 08:00:03 뒤라 두 그룹 다 group.decided 하나(rankingFinal 1)로 끝난다 — 1차 · 2차를 다 불러도 두 번 쓰지 않는다
            listOf(decidedGid, pendingGid).forEach { gid ->
                val e = events.getValue(gid.value)
                assertThat(e.map { it["type"] }).containsExactly("group.decided")
                assertThat(e[0]["rankingFinal"]).isEqualTo("1")
            }
        }

        @Test
        fun `08시와 08시 3초 사이에 기동하면 1차는 곧바로 · 2차는 08시 3초에 띄운다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val (decidedGid, pendingGid) = write(f)
            // 그날 08:00:01.5 — 1차를 놓친 재기동
            val game = f.at(Duration.ofMillis(1_500))
            val scheduler = scheduler(f, game)
            scheduler.start()
            try {
                awaitFinal(f, listOf(decidedGid, pendingGid))
            } finally {
                scheduler.stop()
            }
            val graceEndUs = f.micros(game.graceEndAt(f.date))
            val d = f.events().getValue(decidedGid.value)
            assertThat(d.map { it["type"] }).containsExactly("group.decided", "group.ranking_final")
            assertThat(d[0].getValue("at").toLong()).isLessThan(graceEndUs)
            assertThat(d[1].getValue("at").toLong()).isGreaterThanOrEqualTo(graceEndUs)
            val p = f.events().getValue(pendingGid.value)
            assertThat(p.map { it["type"] to it["rankingFinal"] }).containsExactly("group.decided" to "1")
        }

        @Test
        fun `2차에 실패한 그룹이 있으면 백오프 뒤 2차를 다시 돌아 끝내 확정한다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val (decidedGid, pendingGid) = write(f)
            val game = f.at(Duration.ofSeconds(5))
            val real = f.repository(game)
            val secondCalls = AtomicInteger()
            // 미룬 그룹 호출의 처음 8번이 연결 오류 — 곧바로 뜨는 1차 · 2차가 각각 4번(1 + retry 3)씩 써서 둘 다 실패한다(장애가 이어진 꼴)
            val flaky = object : GroupDayRepository by real {
                override suspend fun runDeadline(gid: GroupId, date: LocalDate, phase: DeadlinePhase): DeadlineOutcome {
                    if (gid == pendingGid && secondCalls.incrementAndGet() <= 8) error("연결 끊김")
                    return real.runDeadline(gid, date, phase)
                }
            }
            val options = DeadlineService.Options(retryBackoff = Duration.ofMillis(10))
            val scheduler = scheduler(
                f, game, DeadlineService(flaky, game, options),
                DeadlineProperties(phaseRetryBackoff = Duration.ofMillis(200), phaseRetryBackoffMax = Duration.ofMillis(400)),
            )
            scheduler.start()
            try {
                awaitFinal(f, listOf(decidedGid, pendingGid))
            } finally {
                scheduler.stop()
            }
            assertThat(secondCalls.get()).isGreaterThan(8)
            assertThat(f.hash(pendingGid)["result"]).isEqualTo("FAILED_DEADLINE")
        }

        @Test
        fun `08시 이전 07시 이후에 기동하면 아무것도 다시 돌지 않는다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val (decidedGid, _) = write(f)
            // 지금 = 07:30 — 어제는 07:00 이 지나 다시 돌지 않고, 오늘 08:00 은 30분 뒤
            val scheduler = scheduler(f, f.at(Duration.ofMinutes(-30)))
            scheduler.start()
            try {
                delay(1_000)
                assertThat(f.hash(decidedGid)).doesNotContainKey("result")
            } finally {
                scheduler.stop()
            }
            assertThat(scheduler.isRunning).isFalse()
        }

        private suspend fun scheduler(
            f: DeadlineFixture,
            game: GameProperties,
            service: DeadlineService = f.service(game),
            properties: DeadlineProperties = DeadlineProperties(),
        ) = DeadlineScheduler(service, GameClock(game, f.redisClock()), game, properties)

        /** 확정될 그룹 · 미뤄질 그룹 하나씩. */
        private suspend fun write(f: DeadlineFixture): Pair<GroupId, GroupId> {
            val base = 1024L * Random.nextLong(1_000, 1_000_000_000)
            val ls = f.us(LocalTime.of(7, 30))
            val decided = GroupId(base)
            val pending = GroupId(base + 1)
            f.write(mapOf(decided to DeadlineFixture.decided(decided.value * 10, ls), pending to DeadlineFixture.pending(pending.value * 10, ls)))
            return decided to pending
        }

        private suspend fun awaitFinal(f: DeadlineFixture, gids: List<GroupId>) {
            val until = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (gids.any { f.hash(it)["rankingFinal"] != "1" }) {
                check(System.nanoTime() < until) { "20초 안에 랭킹이 확정되지 않았다" }
                delay(50)
            }
        }

        private object Unused : GroupDayRepository {
            override suspend fun runDeadline(gid: GroupId, date: LocalDate, phase: DeadlinePhase): DeadlineOutcome = error("부르지 않는다")

            override fun scanDayGroups(bucket: Int, date: LocalDate): Flow<GroupId> = error("부르지 않는다")
        }
    }
