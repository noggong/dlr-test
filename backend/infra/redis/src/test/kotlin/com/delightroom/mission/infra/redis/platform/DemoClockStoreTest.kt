package com.delightroom.mission.infra.redis.platform

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.DemoClockProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.DemoClockRules
import com.delightroom.mission.core.service.DemoClockService
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.infra.redis.RedisDemoClockStore
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** ORACLE platform O41 · O42 · O48 — Redis `demo:clock` 하나를 세 앱이 같이 쓴다 · 경계 완료 표시 `demo:jobs`(LLD platform §4.4-2 · 3 · 5). */
class DemoClockStoreTest {
    private val template = RedisTestContainer.template
    private val keys = RedisKeys(GameProperties())
    private val store = RedisDemoClockStore(template, keys)
    private val zone = ZoneId.of("Asia/Seoul")
    private val demo = DemoClockProperties(syncInterval = Duration.ofMillis(100), jobWait = Duration.ofMillis(300))
    private val started = mutableListOf<DemoClockService>()

    private fun kst(s: String): Instant = OffsetDateTime.parse("${s}+09:00").toInstant()

    private fun clear(): Unit = runBlocking { template.delete(keys.demoClock(), keys.demoJobs(), keys.demoPending()).awaitSingleOrNull() }

    @BeforeEach
    fun setUp() {
        clear()
    }

    @AfterEach
    fun tearDown() {
        started.forEach { it.close() }
        clear()
    }

    private fun service(game: GameProperties, real: String): DemoClockService = service(game, MutableClock(kst(real), zone))

    private fun service(game: GameProperties, clock: MutableClock): DemoClockService =
        DemoClockService(store, game, demo, clock).also { it.start(); started += it }

    @Test
    fun `O42 SET NX · GET · compareAndSet 은 읽은 값과 같을 때만 쓴다`(): Unit = runBlocking {
        assertThat(store.get()).isNull()
        assertThat(store.setIfAbsent(10)).isTrue()
        assertThat(store.setIfAbsent(20)).isFalse()
        assertThat(store.get()).isEqualTo(10)

        assertThat(store.compareAndSet(11, 30)).isFalse()
        assertThat(store.get()).isEqualTo(10)
        assertThat(store.compareAndSet(10, 30)).isTrue()
        assertThat(store.get()).isEqualTo(30)
    }

    @Test
    fun `O42 먼저 쓴 앱의 첫 이동량을 모두 쓰고, 한쪽 이동이 다른 쪽에 한 주기 안에 닿는다`(): Unit = runBlocking {
        val api = GameProperties()
        val worker = GameProperties()
        service(api, "2026-11-11T15:00:00")
        val workerService = service(worker, "2026-11-11T03:00:00") // 다른 후보 — 지고 api 의 값을 쓴다

        assertThat(worker.shift.micros).isEqualTo(api.shift.micros).isEqualTo(store.get())
        // api 의 후보: 15:03 → 다음 날 07:00 = 15시간 57분
        assertThat(api.shift.duration).isEqualTo(Duration.ofHours(15).plusMinutes(57))

        val apiService = started.first()
        val gameNow = apiService.snapshot().gameNow // 2026-11-12 06:57 KST
        apiService.move(DemoClockService.Target.At(gameNow.plusSeconds(170)))

        delay(demo.syncInterval.multipliedBy(3).toMillis())
        assertThat(worker.shift.micros).isEqualTo(api.shift.micros).isEqualTo(store.get())
        assertThat(apiService.snapshot().gameNow).isEqualTo(kst("2026-11-12T06:59:50"))
        // worker 의 실제 시계는 12시간 이르게 두었다 — 같은 이동량이라 게임 시각도 12시간 이르다
        assertThat(workerService.snapshot().gameNow).isEqualTo(kst("2026-11-11T18:59:50"))
    }

    @Test
    fun `O42 앞으로만 · 7일 안 — 어기면 CLOCK_MOVE_REJECTED 이고 값은 그대로`(): Unit = runBlocking {
        val game = GameProperties()
        val s = service(game, "2026-11-11T15:00:00") // 게임 2026-11-12 06:57
        val before = store.get()

        assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.At(kst("2026-11-12T06:00:00"))) } }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.CLOCK_MOVE_REJECTED) }
        assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.At(kst("2026-11-19T06:58:00"))) } }
            .isInstanceOf(BusinessException::class.java) // 7일을 넘는다
        assertThat(store.get()).isEqualTo(before)

        val moved = s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.START))
        assertThat(moved.gameNow).isEqualTo(kst("2026-11-12T06:59:50"))
        assertThat(moved.moveLimit).isEqualTo(kst("2026-11-19T06:59:50"))
    }

    @Test
    fun `O48 표시는 커질 때만 바뀌고 전부 읽힌다`(): Unit = runBlocking {
        val d = LocalDate.parse("2026-11-12")
        assertThat(store.doneDates()).isEmpty()
        assertThat(store.markDone("member", d)).isTrue()
        assertThat(store.markDone("member", d)).isFalse()
        assertThat(store.markDone("member", d.minusDays(1))).isFalse()
        assertThat(store.markDone("deadline-first:0/1", d.minusDays(1))).isTrue()
        assertThat(store.markDone("member", d.plusDays(1))).isTrue()
        assertThat(store.doneDates()).containsExactlyInAnyOrderEntriesOf(mapOf("member" to d.plusDays(1), "deadline-first:0/1" to d.minusDays(1)))
    }

    @Test
    fun `O48 표시가 오지 않으면 첫 경계에서 멈추고 409, 표시를 두고 다시 보내면 목표로`(): Unit = runBlocking {
        val sess = "sess:{900000003}"
        template.opsForValue().set(sess, "jti-c", Duration.ofHours(2)).awaitSingleOrNull()
        try {
            val game = GameProperties()
            val clock = MutableClock(kst("2026-11-11T15:00:00"), zone)
            val s = service(game, clock) // 게임 2026-11-12 06:57 — deadline(07:59:50)은 07:00 을 넘는다
            assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.DEADLINE)) } }
                .isInstanceOfSatisfying(BusinessException::class.java) {
                    assertThat(it.errorCode).isEqualTo(ErrorCode.CLOCK_MOVE_REJECTED)
                    assertThat(it.message).contains("member").contains("2026-11-12")
                }
            // 시각은 첫 경계(07:00)에 멈췄다 — 실제 시계가 멈춰 있어 정확히 그 시각
            assertThat(s.snapshot().gameNow).isEqualTo(kst("2026-11-12T07:00:00"))
            assertThat(store.get()).isEqualTo(game.shift.micros)
            // 멈췄어도 실제로 옮긴 3분만큼 세션 TTL 이 줄었다(P15)
            assertThat(template.getExpire(sess).awaitSingleOrNull()!!).isBetween(Duration.ofHours(2).minusSeconds(185), Duration.ofHours(2).minusSeconds(175))

            assertThat(store.pending()).isEqualTo("member@2026-11-12")

            // 표시 없이 다시 보내면 — 실제 시간이 흘러 07:00 을 지났어도 — 같은 경계를 기다려 다시 409, 시각은 더 가지 않는다
            clock.set(clock.instant().plusSeconds(2))
            assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.DEADLINE)) } }
                .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.message).contains("member") }
            assertThat(s.snapshot().gameNow).isEqualTo(kst("2026-11-12T07:00:02"))

            store.markDone("member", LocalDate.parse("2026-11-12"))
            val moved = s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.DEADLINE))
            assertThat(store.pending()).isNull()
            assertThat(moved.gameNow).isEqualTo(kst("2026-11-12T07:59:50"))
        } finally {
            template.delete(sess).awaitSingleOrNull()
        }
    }

    @Test
    fun `O48 실제 시간으로 막 지난 경계는 표시가 올 때까지 먼저 기다린다`(): Unit = runBlocking {
        val game = GameProperties()
        val clock = MutableClock(kst("2026-11-11T15:00:00"), zone)
        val s = service(game, clock) // 게임 2026-11-12 06:57
        clock.set(kst("2026-11-11T15:03:00.1")) // 게임 07:00:00.1 — 명단 확정이 돌고 있을 틈(job-wait 300ms 안)
        assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.At(kst("2026-11-12T07:30:00"))) } }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.message).contains("member") }
        assertThat(s.snapshot().gameNow).isEqualTo(kst("2026-11-12T07:00:00.1"))
        store.markDone("member", LocalDate.parse("2026-11-12"))
        assertThat(s.move(DemoClockService.Target.At(kst("2026-11-12T07:30:00"))).gameNow).isEqualTo(kst("2026-11-12T07:30:00"))
    }

    @Test
    fun `O42 이미 키가 있으면(볼륨이 남음) 그 값을 쓴다`(): Unit = runBlocking {
        store.setIfAbsent(123_000_000)
        val game = GameProperties()
        service(game, "2026-11-11T15:00:00")
        assertThat(game.shift.micros).isEqualTo(123_000_000)
    }

    @Test
    fun `O41 shift 를 바꾸면 Lua common now 도 같은 만큼 움직인다`(): Unit = runBlocking {
        val scripts = RedisScripts(template)
        val game = GameProperties(clockOffset = Duration.ofSeconds(-60))
        game.shift.set(7_200_000_000L)
        val reply = scripts.eval("platform_now_probe", emptyList(), listOf(game.clockOffsetUs.toString()))
        val (now, time) = reply[0] as Long to reply[1] as Long
        assertThat(time + 7_140_000_000L - now).isBetween(0L, 1_000L)
    }

    @Test
    fun `O42 키를 잃으면 이 앱 값을 다시 심고, 더 작은 값은 반영하지 않고 덮는다 — 시각이 뒤로 가지 않는다`(): Unit = runBlocking {
        val game = GameProperties()
        val s = service(game, "2026-11-11T15:00:00")
        val local = game.shift.micros

        clear()
        s.syncOnce()
        assertThat(store.get()).isEqualTo(local)

        // 재기동한 다른 앱이 새(더 작은) 후보를 심은 꼴
        template.opsForValue().set(keys.demoClock(), "1000").awaitSingleOrNull()
        s.syncOnce()
        assertThat(game.shift.micros).isEqualTo(local)
        assertThat(store.get()).isEqualTo(local)

        // 키가 없어도 이동한다
        clear()
        val moved = s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.START))
        assertThat(moved.gameNow).isEqualTo(kst("2026-11-12T06:59:50"))
        assertThat(store.get()).isEqualTo(game.shift.micros)
    }

    @Test
    fun `O42 시각 이동은 세션 키 TTL 을 옮긴 만큼 줄이고 남는 것이 없으면 지운다`(): Unit = runBlocking {
        val long = "sess:{900000001}"
        val short = "sess:{900000002}"
        template.opsForValue().set(long, "jti-a", Duration.ofHours(2)).awaitSingleOrNull()
        template.opsForValue().set(short, "jti-b", Duration.ofMinutes(10)).awaitSingleOrNull()
        try {
            val game = GameProperties()
            val s = service(game, "2026-11-11T15:00:00") // 게임 06:57
            s.move(DemoClockService.Target.At(kst("2026-11-12T06:59:50"))) // 2분 50초 앞으로
            val ttl = template.getExpire(long).awaitSingleOrNull()!!
            assertThat(ttl).isBetween(Duration.ofHours(2).minusSeconds(175), Duration.ofHours(2).minusSeconds(165))

            assertThat(store.shortenSessions(Duration.ofMinutes(30))).isEqualTo(2)
            assertThat(template.hasKey(short).awaitSingleOrNull()).isFalse()
            assertThat(template.hasKey(long).awaitSingleOrNull()).isTrue()
        } finally {
            template.delete(long, short).awaitSingleOrNull()
        }
    }
}
