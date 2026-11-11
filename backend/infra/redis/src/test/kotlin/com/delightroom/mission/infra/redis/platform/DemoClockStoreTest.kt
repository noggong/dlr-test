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
import java.time.OffsetDateTime
import java.time.ZoneId

/** ORACLE platform O41 · O42 — Redis `demo:clock` 하나를 세 앱이 같이 쓴다(LLD platform §4.4-2 · 3). */
class DemoClockStoreTest {
    private val template = RedisTestContainer.template
    private val keys = RedisKeys(GameProperties())
    private val store = RedisDemoClockStore(template, keys)
    private val zone = ZoneId.of("Asia/Seoul")
    private val demo = DemoClockProperties(syncInterval = Duration.ofMillis(100), moveMargin = Duration.ofSeconds(5))
    private val started = mutableListOf<DemoClockService>()

    private fun kst(s: String): Instant = OffsetDateTime.parse("${s}+09:00").toInstant()

    private fun clear(): Unit = runBlocking { template.delete(keys.demoClock()).awaitSingleOrNull() }

    @BeforeEach
    fun setUp() {
        clear()
    }

    @AfterEach
    fun tearDown() {
        started.forEach { it.close() }
        clear()
    }

    private fun service(game: GameProperties, real: String): DemoClockService =
        DemoClockService(store, game, demo, MutableClock(kst(real), zone)).also { it.start(); started += it }

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
    fun `O42 앞으로만 · 경계 5초 전까지 — 어기면 CLOCK_MOVE_REJECTED 이고 값은 그대로`(): Unit = runBlocking {
        val game = GameProperties()
        val s = service(game, "2026-11-11T15:00:00") // 게임 2026-11-12 06:57
        val before = store.get()

        assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.At(kst("2026-11-12T06:00:00"))) } }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.CLOCK_MOVE_REJECTED) }
        assertThatThrownBy { runBlocking { s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.DEADLINE)) } }
            .isInstanceOf(BusinessException::class.java) // 07:59:50 은 07:00 을 넘는다
        assertThat(store.get()).isEqualTo(before)

        val moved = s.move(DemoClockService.Target.Preset(DemoClockRules.Preset.START))
        assertThat(moved.gameNow).isEqualTo(kst("2026-11-12T06:59:50"))
        assertThat(moved.moveLimit).isEqualTo(kst("2026-11-12T06:59:55"))
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
}
