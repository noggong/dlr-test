package com.delightroom.mission.core.democlock

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.DemoClockRules
import com.delightroom.mission.core.domain.game.DemoClockRules.Preset
import com.delightroom.mission.core.domain.game.DemoJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** ORACLE platform O39 · O40 — 데모 시계의 순수 규칙(prd D25 · LLD platform §4.4). */
class DemoClockRulesTest {
    private fun kst(s: String): Instant = OffsetDateTime.parse("${s}+09:00").toInstant()

    private val lead = Duration.ofMinutes(3)
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
    private val maxSpan = Duration.ofDays(7)

    @Test
    fun `O40 프리셋은 다음 07시 · 08시의 10초 전`() {
        assertThat(DemoClockRules.preset(kst("2026-11-11T06:57:00"), Preset.START, game)).isEqualTo(kst("2026-11-11T06:59:50"))
        assertThat(DemoClockRules.preset(kst("2026-11-11T07:30:00"), Preset.DEADLINE, game)).isEqualTo(kst("2026-11-11T07:59:50"))
        assertThat(DemoClockRules.preset(kst("2026-11-11T08:00:05"), Preset.START, game)).isEqualTo(kst("2026-11-12T06:59:50"))
        assertThat(DemoClockRules.preset(kst("2026-11-11T07:30:00"), Preset.START, game)).isEqualTo(kst("2026-11-12T06:59:50"))
    }

    @ParameterizedTest(name = "O40 {0} 에서 start · deadline 모두 통과")
    @CsvSource("2026-11-11T06:00:00", "2026-11-11T06:57:00", "2026-11-11T07:30:00", "2026-11-11T07:59:57", "2026-11-11T08:00:01", "2026-11-11T08:00:05")
    fun `O40 지금 시각에 상관없이 프리셋 이동은 통과한다`(at: String) {
        val now = kst(at)
        Preset.entries.forEach { p -> assertThat(check(now, DemoClockRules.preset(now, p, game)).ok).`as`("$p").isTrue() }
    }

    @Test
    fun `O40 뒤 · 같은 시각 · max-span 넘음만 거절`() {
        val now = kst("2026-11-11T07:30:00")
        assertThat(check(now, kst("2026-11-11T07:29:59")).ok).isFalse()
        assertThat(check(now, now).ok).isFalse()
        assertThat(check(now, kst("2026-11-11T08:00:01")).ok).isTrue()
        assertThat(check(now, now.plus(maxSpan)).ok).isTrue()
        assertThat(check(now, now.plus(maxSpan).plusNanos(1_000)).ok).isFalse()
        assertThat(DemoClockRules.moveLimit(now, maxSpan)).isEqualTo(kst("2026-11-18T07:30:00"))
    }

    @Test
    fun `O40 07시 30분에서 start 는 그날 08시 · 08시 0분 3초를 지난다`() {
        val now = kst("2026-11-11T07:30:00")
        val b = DemoClockRules.boundaries(now, DemoClockRules.preset(now, Preset.START, game), game)
        assertThat(b.map { it.at to it.job }).containsExactly(
            kst("2026-11-11T08:00:00") to DemoJob.DEADLINE_FIRST,
            kst("2026-11-11T08:00:03") to DemoJob.DEADLINE_SECOND,
        )
        assertThat(b.map { it.date }.toSet()).containsExactly(LocalDate.parse("2026-11-11"))
    }

    @Test
    fun `O40 06시에서 08시 30분은 07시 명단 · 08시 · 08시 0분 3초 순서이고 사흘이면 9개`() {
        val now = kst("2026-11-11T06:00:00")
        assertThat(DemoClockRules.boundaries(now, kst("2026-11-11T08:30:00"), game).map { it.job })
            .containsExactly(DemoJob.MEMBER, DemoJob.DEADLINE_FIRST, DemoJob.DEADLINE_SECOND)
        val three = DemoClockRules.boundaries(now, kst("2026-11-13T08:30:00"), game)
        assertThat(three).hasSize(9)
        assertThat(three.map { it.at }).isSorted()
        assertThat(three.map { it.date }.distinct()).containsExactly(
            LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13"),
        )
        // 목표와 같은 시각의 경계는 넣고 지금과 같은 시각의 경계는 넣지 않는다
        assertThat(DemoClockRules.boundaries(now, kst("2026-11-11T07:00:00"), game).map { it.job }).containsExactly(DemoJob.MEMBER)
        assertThat(DemoClockRules.boundaries(kst("2026-11-11T07:00:00"), kst("2026-11-11T07:59:59"), game)).isEmpty()
        // 경계 사이만 옮기면 기다릴 것이 없다
        assertThat(DemoClockRules.boundaries(kst("2026-11-11T07:30:00"), kst("2026-11-11T07:59:50"), game)).isEmpty()
    }

    @Test
    fun `O40 표시 판정 — 명단은 member 날짜 이상, 마감은 어떤 n 의 모든 인스턴스가 날짜 이상`() {
        val d = LocalDate.parse("2026-11-11")
        val member = DemoClockRules.Boundary(kst("2026-11-11T07:00:00"), DemoJob.MEMBER, d)
        val first = DemoClockRules.Boundary(kst("2026-11-11T08:00:00"), DemoJob.DEADLINE_FIRST, d)
        assertThat(DemoClockRules.isDone(emptyMap(), member)).isFalse()
        assertThat(DemoClockRules.isDone(mapOf("member" to d.minusDays(1)), member)).isFalse()
        assertThat(DemoClockRules.isDone(mapOf("member" to d), member)).isTrue()
        assertThat(DemoClockRules.isDone(mapOf("member" to d.plusDays(1)), member)).isTrue()

        assertThat(DemoClockRules.isDone(mapOf("deadline-first:0/1" to d), first)).isTrue()
        assertThat(DemoClockRules.isDone(mapOf("deadline-second:0/1" to d), first)).isFalse()
        assertThat(DemoClockRules.isDone(mapOf("deadline-first:0/2" to d), first)).isFalse()
        assertThat(DemoClockRules.isDone(mapOf("deadline-first:0/2" to d, "deadline-first:1/2" to d.minusDays(1)), first)).isFalse()
        assertThat(DemoClockRules.isDone(mapOf("deadline-first:0/2" to d, "deadline-first:1/2" to d), first)).isTrue()
        // 인스턴스 수를 바꾼 뒤 남은 옛 필드는 막지 않는다
        assertThat(DemoClockRules.isDone(mapOf("deadline-first:0/2" to d.minusDays(5), "deadline-first:0/1" to d), first)).isTrue()
    }

    private fun check(now: Instant, target: Instant) = DemoClockRules.check(now, target, maxSpan)
}
