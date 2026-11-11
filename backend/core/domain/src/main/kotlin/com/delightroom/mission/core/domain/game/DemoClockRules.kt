package com.delightroom.mission.core.domain.game

import com.delightroom.mission.core.config.GameProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 로컬 데모 시계의 순수 규칙(prd D25 · D26 · LLD platform §4.4 · SPEC platform P10 · P12 · P16). 시각은 모두 게임 시각이다(이동량 [first] 만 실제 시각을 받는다). */
object DemoClockRules {
    /** 프리셋이 경계 앞에 서는 간격 — `start` = 07:00 − 10초 · `deadline` = 08:00 − 10초. */
    val PRESET_LEAD: Duration = Duration.ofSeconds(10)

    enum class Preset { START, DEADLINE }

    /** 이동 검사 결과 — 통과면 [ok]. 거절이면 [reason] 이 이유. */
    data class Check(val ok: Boolean, val reason: String)

    /** 이동이 지나는 경계 — 게임 시각 [at] 에 [date] 의 [job] 이 돈다. */
    data class Boundary(val at: Instant, val job: DemoJob, val date: LocalDate)

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

    /** 지금 옮길 수 있는 가장 늦은 시각 = [now] + [maxSpan](P12). */
    fun moveLimit(now: Instant, maxSpan: Duration): Instant = now.plus(maxSpan)

    /** 프리셋 시각 — 07:00(`START`) · 08:00(`DEADLINE`)의 [PRESET_LEAD] 전 중 [now] 보다 뒤인 가장 이른 것(07:59:57 의 `deadline` 은 다음 날 07:59:50). */
    fun preset(now: Instant, preset: Preset, game: GameProperties): Instant {
        val today = now.atZone(game.zone).toLocalDate()
        val at = { d: LocalDate -> (if (preset == Preset.START) game.startAt(d) else game.deadlineAt(d)).minus(PRESET_LEAD) }
        return generateSequence(today) { it.plusDays(1) }.map(at).first { it > now }
    }

    /** P12 — `now < target ≤ now + maxSpan` 이면 통과. 지금이 어느 시각이든 이 밖의 조건은 없다(prd D26). */
    fun check(now: Instant, target: Instant, maxSpan: Duration): Check {
        if (target <= now) return Check(false, "앞으로만 옮긴다: now=$now target=$target")
        val limit = moveLimit(now, maxSpan)
        if (target > limit) return Check(false, "한 번에 ${maxSpan.toDays()}일($limit)까지만 옮긴다: target=$target")
        return Check(true, "")
    }

    /**
     * [from] < `b` ≤ [to] 인 경계를 시각 순서로(P12 · LLD §4.4-3) — 날마다 07:00 명단 · 08:00 마감 1차 · 08:00:03 2차.
     * 이동은 이 경계마다 멈춰 그 일이 끝나기를 기다린다.
     */
    fun boundaries(from: Instant, to: Instant, game: GameProperties): List<Boundary> {
        if (to <= from) return emptyList()
        val first = from.atZone(game.zone).toLocalDate().minusDays(1)
        val last = to.atZone(game.zone).toLocalDate().plusDays(1)
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { it <= last }
            .flatMap { d ->
                sequenceOf(
                    Boundary(game.startAt(d), DemoJob.MEMBER, d),
                    Boundary(game.deadlineAt(d), DemoJob.DEADLINE_FIRST, d),
                    Boundary(game.graceEndAt(d), DemoJob.DEADLINE_SECOND, d),
                )
            }
            .filter { it.at > from && it.at <= to }
            .sortedBy { it.at }
            .toList()
    }

    /** [job] 의 [date] 경계. */
    fun boundary(job: DemoJob, date: LocalDate, game: GameProperties): Boundary {
        val at = when (job) {
            DemoJob.MEMBER -> game.startAt(date)
            DemoJob.DEADLINE_FIRST -> game.deadlineAt(date)
            DemoJob.DEADLINE_SECOND -> game.graceEndAt(date)
        }
        return Boundary(at, job, date)
    }

    /** `demo:pending` 값 — `<key>@<날짜>`. */
    fun pendingValue(b: Boundary): String = "${b.job.key}@${b.date}"

    /** [pendingValue] 를 되읽는다. 모양이 아니면 null. */
    fun parsePending(value: String, game: GameProperties): Boundary? {
        val job = DemoJob.entries.firstOrNull { it.key == value.substringBefore('@') } ?: return null
        val date = runCatching { LocalDate.parse(value.substringAfter('@')) }.getOrNull() ?: return null
        return boundary(job, date, game)
    }

    /**
     * 표시 [done](`demo:jobs` — 필드 → 날짜)로 보아 [b] 의 일이 끝났는가(P16). 명단은 `member` ≥ 날짜,
     * 마감은 `<key>:<i>/<n>` 필드의 어떤 `n` 에 대해 `0 ≤ i < n` 이 모두 날짜 이상(인스턴스 수를 바꾼 뒤 남은 옛 필드가 막지 않게).
     */
    fun isDone(done: Map<String, LocalDate>, b: Boundary): Boolean {
        if (b.job == DemoJob.MEMBER) return done[b.job.key]?.let { it >= b.date } == true
        val prefix = "${b.job.key}:"
        val counts = done.keys.filter { it.startsWith(prefix) }.mapNotNull { it.substringAfter('/').toIntOrNull() }.toSet()
        return counts.any { n -> (0 until n).all { i -> done[b.job.field(i, n)]?.let { it >= b.date } == true } }
    }
}
