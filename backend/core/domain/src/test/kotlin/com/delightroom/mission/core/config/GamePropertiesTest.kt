package com.delightroom.mission.core.config

import com.delightroom.mission.core.testfixture.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.time.OffsetDateTime

/** ORACLE platform O8 — 앱 시계 시각 주입. 설정은 앱과 같은 길(`@EnableConfigurationProperties`)로 묶는다. */
class GamePropertiesTest {
    @EnableConfigurationProperties(GameProperties::class)
    class PropertiesOnly

    private fun bind(vararg pairs: Pair<String, String>): GameProperties {
        var bound: GameProperties? = null
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly::class.java)
            .withPropertyValues(*pairs.map { (k, v) -> "$k=$v" }.toTypedArray())
            .run { context -> bound = context.getBean(GameProperties::class.java) }
        return requireNotNull(bound)
    }

    @Test
    fun `O8 설정이 없으면 오프셋 0 · 버킷 1024`() {
        val properties = bind()

        assertThat(properties.clockOffset).isEqualTo(Duration.ZERO)
        assertThat(properties.clockOffsetUs).isZero()
        assertThat(properties.bucketCount).isEqualTo(1024)
    }

    @Test
    fun `O8 game_clock-offset 만큼 now 가 옮겨진다`() {
        val properties = bind("game.clock-offset" to "-30m")
        val clock = MutableClock(OffsetDateTime.parse("2026-11-11T07:10:00+09:00").toInstant(), properties.zone)

        assertThat(properties.clockOffset).isEqualTo(Duration.ofMinutes(-30))
        assertThat(properties.now(clock)).isEqualTo(OffsetDateTime.parse("2026-11-11T06:40:00+09:00").toInstant())
        assertThat(properties.clockOffsetUs).isEqualTo(-1_800_000_000L)

        clock.advance(Duration.ofMinutes(25))
        assertThat(properties.now(clock)).isEqualTo(OffsetDateTime.parse("2026-11-11T07:05:00+09:00").toInstant())
    }

    @Test
    fun `O8 clockOffsetUs 는 µs 아래를 내림한다`() {
        assertThat(bind("game.clock-offset" to "PT1.0000019S").clockOffsetUs).isEqualTo(1_000_001L)
        assertThat(GameProperties(clockOffset = Duration.ofNanos(-1)).clockOffsetUs).isEqualTo(-1L)
        assertThat(GameProperties(clockOffset = Duration.ofHours(2)).clockOffsetUs).isEqualTo(7_200_000_000L)
    }

    @Test
    fun `O8 bucketOf 는 gid mod 1024`() {
        val properties = GameProperties()

        assertThat(properties.bucketOf(1025)).isEqualTo(1)
        assertThat(properties.bucketOf(1024)).isEqualTo(0)
        assertThat(properties.bucketOf(7)).isEqualTo(7)
    }

    @Test
    fun `O8 잘못된 값은 기동 때 실패한다`() {
        assertThatThrownBy { GameProperties(bucketCount = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GameProperties(clockOffset = Duration.ofSeconds(Long.MAX_VALUE / 1_000)) }
            .isInstanceOf(ArithmeticException::class.java)
    }
}
