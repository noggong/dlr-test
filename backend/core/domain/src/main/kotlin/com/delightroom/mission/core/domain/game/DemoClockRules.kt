package com.delightroom.mission.core.domain.game

import com.delightroom.mission.core.config.GameProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 로컬 데모 시계의 순수 규칙(prd D25 · LLD platform §4.4 · SPEC platform P10 · P12). 시각은 모두 게임 시각이다(이동량 [first] 만 실제 시각을 받는다). */
object DemoClockRules {
    /** 프리셋이 경계 앞에 서는 간격 — `start` = 07:00 − 10초 · `deadline` = 08:00 − 10초. */
    val PRESET_LEAD: Duration = Duration.ofSeconds(10)

    enum class Preset { START, DEADLINE }

    /** 이동 검사 결과 — 통과면 [ok]. 거절이면 [reason] 이 이유. */
    data class Check(val ok: Boolean, val reason: String)

    /**
     * 첫 런타임 이동량(P10 · LLD §4.4-1): 실제 [realNow] + [lead] 가 게임 `D 07:00` 이 되게 한다.
     * `D` 는 [realNow] 의 KST 날짜부터, 이동 총량(`clock-offset` + 결과)이 `start-time − join-cutoff` 이상이고 결과가 0 이상인 가장 이른 날짜다.
     */
    fun first(realNow: Instant, lead: Duration, game: GameProperties): Duration {
        val gap = Duration.between(game.joinCutoff, game.startTime)
        val target = realNow.plus(lead)
        var date = realNow.atZone(game.zone).toLocalDate()
        while (true) {
            val total = Duration.between(target, game.startAt(date))
            val shift = total.minus(game.clockOffset)
            if (total >= gap && !shift.isNegative) return shift
            date = date.plusDays(1)
        }
    }

    /** [now] 뒤(>) 가장 이른 경계 — 그날 · 다음 날의 07:00 명단 확정 · 08:00 마감 · 08:00:03 랭킹 확정. */
    fun nextBoundary(now: Instant, game: GameProperties): Instant {
        val today = now.atZone(game.zone).toLocalDate()
        return (-1L..2L).asSequence()
            .map { today.plusDays(it) }
            .flatMap { boundaries(it, game) }
            .filter { it > now }
            .min()
    }

    /** 지금 옮길 수 있는 가장 늦은 시각 = 다음 경계 − [margin]. */
    fun moveLimit(now: Instant, margin: Duration, game: GameProperties): Instant = nextBoundary(now, game).minus(margin)

    /** 프리셋 시각 — [now] 뒤 가장 이른 07:00(`START`) · 08:00(`DEADLINE`)의 [PRESET_LEAD] 전. */
    fun preset(now: Instant, preset: Preset, game: GameProperties): Instant {
        val today = now.atZone(game.zone).toLocalDate()
        val at = { d: LocalDate -> if (preset == Preset.START) game.startAt(d) else game.deadlineAt(d) }
        val next = generateSequence(today) { it.plusDays(1) }.map(at).first { it > now }
        return next.minus(PRESET_LEAD)
    }

    /** P12 — `now < target ≤ 다음 경계 − margin` 이면 통과. */
    fun check(now: Instant, target: Instant, margin: Duration, game: GameProperties): Check {
        if (target <= now) return Check(false, "앞으로만 옮긴다: now=$now target=$target")
        val limit = moveLimit(now, margin, game)
        if (target > limit) return Check(false, "다음 경계 ${nextBoundary(now, game)} 의 ${margin.seconds}초 전($limit)까지만 옮긴다: target=$target")
        return Check(true, "")
    }

    private fun boundaries(date: LocalDate, game: GameProperties): Sequence<Instant> =
        sequenceOf(game.startAt(date), game.deadlineAt(date), game.graceEndAt(date))
}
