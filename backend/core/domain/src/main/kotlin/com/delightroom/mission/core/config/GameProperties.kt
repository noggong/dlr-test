package com.delightroom.mission.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 게임 규칙의 수치(prd §1 · §3 · §5). 기본값은 PRD 원문 값이다. */
@ConfigurationProperties("game")
data class GameProperties(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    val startTime: LocalTime = LocalTime.of(7, 0),
    val deadlineTime: LocalTime = LocalTime.of(8, 0),
    /** 마감 직전 제출 가드 유예(결정 D1 · R-D2). */
    val submitGrace: Duration = Duration.ofSeconds(3),
    val minGroupSize: Int = 2,
    val maxGroupSize: Int = 10,
    val questionsPerMission: Int = 3,
    val passesPerDay: Int = 1,
    /**
     * 시각 주입(설정 `game.clock-offset` · HLD platform §8) — 07:00 을 기다리지 않고 검증하는 테스트 · 시연용, 운영은 0.
     * 앱은 [now] 로, Lua 는 ARGV `offsetUs`([clockOffsetUs])로 같은 값을 더한다.
     */
    val clockOffset: Duration = Duration.ZERO,
    /** 그룹 버킷 수 — `b = gid mod bucketCount`, Redis 해시태그 `{b}` · Stream `ev:{b}` 의 수(HLD platform §4.1). */
    val bucketCount: Int = 1024,
    /** 지각 기준 — 시작 시각 + 이 값보다 늦게 미션을 시작하면 지각(결정 D8). */
    val lateGrace: Duration = Duration.ofSeconds(3),
    /** 은행에 난이도마다 넣는 문제 수(결정 D10). */
    val bankSizePerDifficulty: Int = 20,
    /** `worker: prepare-questions` 실행 시각(결정 D18) — 오름차순, 모두 [startTime] 전. */
    val prepareTimes: List<LocalTime> = listOf(LocalTime.of(6, 30), LocalTime.of(6, 50)),
    /** 결과 캐시 `result:{<gid>}` 의 TTL(25시간 — LLD deadline-result §2.1). 다음 게임 결과가 들어올 때까지 남는다. */
    val resultCacheTtl: Duration = Duration.ofHours(25),
) {
    init {
        require(bucketCount > 0) { "game.bucket-count 는 1 이상이어야 한다: $bucketCount" }
        require(!lateGrace.isNegative) { "game.late-grace 는 0 이상이어야 한다: $lateGrace" }
        require(bankSizePerDifficulty > 0) { "game.bank-size-per-difficulty 는 1 이상이어야 한다: $bankSizePerDifficulty" }
        require(prepareTimes.zipWithNext().all { (a, b) -> a < b } && prepareTimes.all { it < startTime }) {
            "game.prepare-times 는 오름차순이고 모두 ${startTime} 전이어야 한다: $prepareTimes"
        }
    }

    /**
     * Lua ARGV `offsetUs` — [clockOffset] 을 µs 로. µs 아래는 내림한다(음수 오프셋도 같은 방향).
     * 만들 때 한 번 계산하므로 µs 로 넘치는 오프셋은 요청 처리 중이 아니라 기동(바인딩) 때 실패한다.
     */
    val clockOffsetUs: Long =
        Math.addExact(Math.multiplyExact(clockOffset.seconds, 1_000_000L), clockOffset.nano / 1_000L)

    /** 앱이 「지금」을 고르는 하나뿐인 길 — [Clock] 빈은 오프셋을 모른다. */
    fun now(clock: Clock): Instant = clock.instant().plus(clockOffset)

    /** [date] 게임의 시작 시각(07:00:00 KST). */
    fun startAt(date: LocalDate): Instant = date.atTime(startTime).atZone(zone).toInstant()

    /** [date] 게임의 마감 시각(08:00:00 KST — Lua `deadline` 의 `deadlineUs`). */
    fun deadlineAt(date: LocalDate): Instant = date.atTime(deadlineTime).atZone(zone).toInstant()

    /** [date] 게임의 유예 끝 = 랭킹 확정 시각(08:00:03 — `deadlineUs + submitGrace`, Lua `deadline` 의 `graceEndUs`). */
    fun graceEndAt(date: LocalDate): Instant = deadlineAt(date).plus(submitGrace)

    /** 그룹 버킷 `gid mod bucketCount`(HLD platform §4.1). */
    fun bucketOf(groupId: Long): Int = Math.floorMod(groupId, bucketCount)
}
