package com.delightroom.mission.core.domain.game

import com.delightroom.mission.core.config.GameProperties
import java.time.Clock
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
    /** 지금(시각 주입 `game.clock-offset` 포함 — [GameProperties.now]). */
    fun now(): Instant = properties.now(clock)

    /** 게임 정보의 날짜(GS1): KST 로 그 날짜 08:00:00 전이면 그 날짜, 08:00:00 부터 다음 날. */
    fun infoDate(now: Instant): LocalDate {
        val date = playDate(now)
        return if (now < deadlineAt(date)) date else date.plusDays(1)
    }

    /** 문제은행 · 진입 신호의 날짜(GS1): 서버 시각의 KST 날짜. */
    fun playDate(now: Instant): LocalDate = now.atZone(properties.zone).toLocalDate()

    /** 07:00:00 — 미션 시작 · 그날 명단 기준(`joined_at <` 이 시각). */
    fun startAt(date: LocalDate): Instant = date.atTime(properties.startTime).atZone(properties.zone).toInstant()

    /** 07:00:03 — 이 시각보다 늦게(`>`) 시작하면 지각(D8). */
    fun lateAt(date: LocalDate): Instant = startAt(date).plus(properties.lateGrace)

    /** 08:00:00 — 마감. 은행 키 `PXAT` 도 이 시각이다(LLD §2.1). */
    fun deadlineAt(date: LocalDate): Instant = properties.deadlineAt(date)

    /** `worker: prepare-questions` 실행 시각들(06:30 · 06:50 — D18). */
    fun prepareAt(date: LocalDate): List<Instant> =
        properties.prepareTimes.map { date.atTime(it).atZone(properties.zone).toInstant() }

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
