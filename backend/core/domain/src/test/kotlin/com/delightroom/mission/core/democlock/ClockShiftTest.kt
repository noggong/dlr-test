package com.delightroom.mission.core.democlock

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** ORACLE platform O41 · O43 — 런타임 이동량이 「지금」 · Lua `offsetUs` 에 더해지고, 잡의 기다림이 이동에 깬다. */
class ClockShiftTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val real = Instant.parse("2026-11-10T22:00:00Z") // 07:00 KST

    @Test
    fun `O41 now 와 clockOffsetUs 가 clock-offset + shift 만큼 함께 움직인다`() {
        val game = GameProperties(clockOffset = Duration.ofSeconds(90))
        val clock = MutableClock(real, zone)
        assertThat(game.now(clock)).isEqualTo(real.plusSeconds(90))
        assertThat(game.clockOffsetUs).isEqualTo(90_000_000L)

        game.shift.set(3_600_000_001L)

        assertThat(game.now(clock)).isEqualTo(real.plusSeconds(90 + 3_600).plusNanos(1_000))
        assertThat(game.clockOffsetUs).isEqualTo(3_690_000_001L)
        assertThat(GameClock(game, clock).now()).isEqualTo(game.now(clock))
    }

    @Test
    fun `O41 shift 는 설정 값이 아니라 equals 에 들지 않고 기본 0`() {
        assertThat(GameProperties().shift.micros).isZero()
        val a = GameProperties().also { it.shift.set(5) }
        assertThat(a).isEqualTo(GameProperties())
    }

    @Test
    fun `O43 pauseUntil 은 이동이 일어나면 기다림 상한보다 먼저 돌아오고 지났으면 true`(): Unit = runBlocking {
        val game = GameProperties()
        val clock = GameClock(game, MutableClock(real, zone))
        val at = real.plusSeconds(30 * 60) // 07:30

        val started = System.nanoTime()
        val waiting = async { clock.pauseUntil(at, Duration.ofMinutes(1)) }
        delay(100)
        game.shift.set(Duration.ofMinutes(31).toNanos() / 1_000)

        assertThat(waiting.await()).isTrue()
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1))
    }

    @Test
    fun `O43 pauseUntil 은 이동이 아직 그 시각에 못 미치면 false — 부른 쪽이 다시 잰다`(): Unit = runBlocking {
        val game = GameProperties()
        val clock = GameClock(game, MutableClock(real, zone))
        val waiting = async { clock.pauseUntil(real.plusSeconds(3_600), Duration.ofMinutes(1)) }
        delay(100)
        game.shift.set(1_000_000)

        assertThat(waiting.await()).isFalse()
        assertThat(clock.pauseUntil(real, Duration.ofMinutes(1))).isTrue()
    }
}
