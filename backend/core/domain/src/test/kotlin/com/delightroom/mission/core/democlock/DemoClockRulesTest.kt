package com.delightroom.mission.core.democlock

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.DemoClockRules
import com.delightroom.mission.core.domain.game.DemoClockRules.Preset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** ORACLE platform O39 · O40 — 데모 시계의 순수 규칙(prd D25 · LLD platform §4.4). */
class DemoClockRulesTest {
    private fun kst(s: String): Instant = OffsetDateTime.parse("${s}+09:00").toInstant()

    private val lead = Duration.ofMinutes(3)
    private val margin = Duration.ofSeconds(5)
    private val gap = Duration.ofMinutes(10)

    @ParameterizedTest(name = "O39 실제 {0} · clock-offset {1}")
    @CsvSource(
        "2026-11-11T03:00:00, PT0S",
        "2026-11-11T06:40:00, PT0S",
        "2026-11-11T06:48:00, PT0S",
        "2026-11-11T06:55:00, PT0S",
        "2026-11-11T07:30:00, PT0S",
        "2026-11-11T15:00:00, PT0S",
        "2026-11-11T15:00:00, PT1H",
        "2026-11-11T03:00:00, PT30H",
        "2026-11-11T03:00:00, PT-2H",
    )
    fun `O39 첫 이동량은 기동 + lead 를 게임 07시로 두고 총량 10분 이상 · 이동량 0 이상인 가장 이른 날짜`(real: String, base: String) {
        val game = GameProperties(clockOffset = Duration.parse(base))
        val r = kst(real)

        val shift = DemoClockRules.first(r, lead, game)

        val total = game.clockOffset.plus(shift)
        val gameAtLead = r.plus(lead).plus(total)
        val date = gameAtLead.atZone(game.zone).toLocalDate()
        assertThat(gameAtLead).isEqualTo(game.startAt(date))
        assertThat(total).isGreaterThanOrEqualTo(gap)
        assertThat(shift.isNegative).isFalse()
        // 하루 이른 날짜는 조건을 어긴다(가장 이른 날짜)
        val earlierTotal = total.minusDays(1)
        assertThat(earlierTotal < gap || earlierTotal.minus(game.clockOffset).isNegative).isTrue()
    }

    @Test
    fun `O39 실제 06시 40분이면 오늘 07시 — 이동 17분 · 가입 마감 06시 50분 전에 가입한 셈이 된다`() {
        val shift = DemoClockRules.first(kst("2026-11-11T06:40:00"), lead, GameProperties())
        assertThat(shift).isEqualTo(Duration.ofMinutes(17))
    }

    @Test
    fun `O39 실제 06시 55분이면 다음 날 07시 — 오늘은 이동이 10분보다 작다`() {
        val shift = DemoClockRules.first(kst("2026-11-11T06:55:00"), lead, GameProperties())
        assertThat(shift).isEqualTo(Duration.ofHours(24).plusMinutes(2))
    }

    private val game = GameProperties()

    @Test
    fun `O40 프리셋은 다음 07시 · 08시의 10초 전`() {
        assertThat(DemoClockRules.preset(kst("2026-11-11T06:57:00"), Preset.START, game)).isEqualTo(kst("2026-11-11T06:59:50"))
        assertThat(DemoClockRules.preset(kst("2026-11-11T07:30:00"), Preset.DEADLINE, game)).isEqualTo(kst("2026-11-11T07:59:50"))
        assertThat(DemoClockRules.preset(kst("2026-11-11T08:00:05"), Preset.START, game)).isEqualTo(kst("2026-11-12T06:59:50"))
        assertThat(DemoClockRules.preset(kst("2026-11-11T07:30:00"), Preset.START, game)).isEqualTo(kst("2026-11-12T06:59:50"))
    }

    @Test
    fun `O40 07시 30분에서 deadline 은 통과하고 start 는 08시를 넘어 거절`() {
        val now = kst("2026-11-11T07:30:00")
        assertThat(check(now, DemoClockRules.preset(now, Preset.DEADLINE, game)).ok).isTrue()
        assertThat(check(now, DemoClockRules.preset(now, Preset.START, game)).ok).isFalse()
    }

    @Test
    fun `O40 08시 0분 5초에서 start 는 통과 — 다음 날 06시 59분 50초 · 06시 30분 06시 50분은 건너뛰어도 된다`() {
        val now = kst("2026-11-11T08:00:05")
        assertThat(check(now, kst("2026-11-12T06:59:50")).ok).isTrue()
        assertThat(DemoClockRules.moveLimit(now, margin, game)).isEqualTo(kst("2026-11-12T06:59:55"))
    }

    @Test
    fun `O40 뒤 · 같은 시각 · 경계 넘음 · 경계 5초 안은 거절`() {
        val now = kst("2026-11-11T07:30:00")
        assertThat(check(now, kst("2026-11-11T07:29:59")).ok).isFalse()
        assertThat(check(now, now).ok).isFalse()
        assertThat(check(now, kst("2026-11-11T07:59:55")).ok).isTrue()
        assertThat(check(now, kst("2026-11-11T07:59:55.000001")).ok).isFalse()
        assertThat(check(now, kst("2026-11-11T08:00:01")).ok).isFalse()
        // 08:00 ~ 08:00:03 사이 · 경계 5초 안에서는 어디로도 못 간다
        listOf("2026-11-11T07:59:57", "2026-11-11T08:00:01").forEach { n ->
            val t = kst(n)
            assertThat(DemoClockRules.moveLimit(t, margin, game)).isBefore(t)
            assertThat(check(t, t.plusMillis(1)).ok).isFalse()
        }
        // 06:57 에서는 07:00 이 다음 경계
        assertThat(DemoClockRules.nextBoundary(kst("2026-11-11T06:57:00"), game)).isEqualTo(kst("2026-11-11T07:00:00"))
        assertThat(DemoClockRules.nextBoundary(kst("2026-11-11T08:00:01"), game)).isEqualTo(kst("2026-11-11T08:00:03"))
    }

    private fun check(now: Instant, target: Instant) = DemoClockRules.check(now, target, margin, game)
}
