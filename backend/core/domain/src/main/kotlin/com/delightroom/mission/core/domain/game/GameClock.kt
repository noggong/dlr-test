package com.delightroom.mission.core.domain.game

import com.delightroom.mission.core.config.GameProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 게임 날짜 고르기(SPEC GS1)와 그 날짜의 경계 시각(LLD game-session §1).
 * 경계 시각은 [Instant] 와 Lua ARGV 용 epoch µs 로 둘 다 준다 — Lua 의 `t` 는 Redis `TIME`(µs)이다.
 */
class GameClock(
    private val properties: GameProperties,
    private val clock: Clock,
) {
    /** 지금(시각 주입 `game.clock-offset` · 런타임 이동량 포함 — [GameProperties.now]). */
    fun now(): Instant = properties.now(clock)

    /**
     * 게임 시각 [at] 까지 실제 시간으로 최대 [max] 기다린다(잡의 기다림 — LLD platform §4.4-4). [at] 이 지났으면 곧바로 true.
     * 기다리는 동안 런타임 이동량이 바뀌면(데모 시각 이동) 바로 돌아온다 — 부른 쪽은 false 면 남은 시간을 다시 재고 또 부른다.
     * 이동량을 먼저 읽고 남은 시간을 재므로 그 사이의 이동도 놓치지 않는다. 운영(이동 없음)에서는 `delay(min(남은 시간, max))` 와 같다.
     */
    suspend fun pauseUntil(at: Instant, max: Duration): Boolean {
        val seen = properties.shift.micros
        val left = Duration.between(now(), at)
        if (left <= Duration.ZERO) return true
        properties.shift.awaitChange(seen, minOf(left, max))
        return now() >= at
    }

    /** 게임 정보의 날짜(GS1): KST 로 그 날짜 08:00:00 전이면 그 날짜, 08:00:00 부터 다음 날. */
    fun infoDate(now: Instant): LocalDate {
        val date = playDate(now)
        return if (now < deadlineAt(date)) date else date.plusDays(1)
    }

    /** 문제은행 · 진입 신호의 날짜(GS1): 서버 시각의 KST 날짜. */
    fun playDate(now: Instant): LocalDate = now.atZone(properties.zone).toLocalDate()

    /** 07:00:00 — 미션 시작. 명단을 만드는 시각이다(기준 시각은 [joinCutoffAt]). */
    fun startAt(date: LocalDate): Instant = date.atTime(properties.startTime).atZone(properties.zone).toInstant()

    /** 07:00:03 — 이 시각보다 늦게(`>`) 시작하면 지각(D8). */
    fun lateAt(date: LocalDate): Instant = startAt(date).plus(properties.lateGrace)

    /** 08:00:00 — 마감. 은행 키 만료도 이 시각이다(남은 ms 로 `PX` — LLD v6 §2.1). */
    fun deadlineAt(date: LocalDate): Instant = properties.deadlineAt(date)

    /** `worker: prepare-questions` 실행 시각들(06:30 · 06:50 — D18). */
    fun prepareAt(date: LocalDate): List<Instant> =
        properties.prepareTimes.map { date.atTime(it).atZone(properties.zone).toInstant() }

    /** 06:50:00 — 그날 명단 기준(`joined_at <` 이 시각, prd D24 · LLD §6 Q4). 명단을 만드는 세 길이 모두 이 값을 쓴다. */
    fun joinCutoffAt(date: LocalDate): Instant = date.atTime(properties.joinCutoff).atZone(properties.zone).toInstant()

    /** 가입 시각 → 첫 참가 게임 날짜(SPEC auth-signup AS19): 그 KST 날짜의 [joinCutoffAt] 전이면 그 날짜, 아니면 다음 날. */
    fun firstGameDate(joinedAt: Instant): LocalDate {
        val date = joinedAt.atZone(properties.zone).toLocalDate()
        return if (joinedAt < joinCutoffAt(date)) date else date.plusDays(1)
    }

    /** 명단 · 그룹 Hash · `day:` 키의 만료 = 다음 날 08:00:00(LLD §2.1). */
    fun rosterExpireAt(date: LocalDate): Instant = deadlineAt(date.plusDays(1))

    fun startUs(date: LocalDate): Long = epochMicros(startAt(date))

    fun lateUs(date: LocalDate): Long = epochMicros(lateAt(date))

    fun deadlineUs(date: LocalDate): Long = epochMicros(deadlineAt(date))

    companion object {
        /** epoch µs — µs 아래는 내림한다. */
        fun epochMicros(instant: Instant): Long =
            Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1_000L)
    }
}
