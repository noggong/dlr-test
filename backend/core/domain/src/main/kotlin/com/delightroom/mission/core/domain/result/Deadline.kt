package com.delightroom.mission.core.domain.result

import com.delightroom.mission.core.domain.group.GroupId
import java.time.Duration
import java.time.LocalDate

/** 마감 처리 단계(LLD deadline-result §3.2) — 08:00:00 1차 · 08:00:03 2차. [value] 는 Lua `deadline` 의 ARGV `phase`. */
enum class DeadlinePhase(val value: Int) {
    FIRST(1),
    SECOND(2),
}

/** Lua `deadline` 의 반환 코드(LLD deadline-result §4.1 판정 순서). */
enum class DeadlineCode {
    /** 시각 전 — 아무것도 바꾸지 않았다. */
    EARLY,

    /** 그룹 Hash 가 없다(1명 그룹 · 만료). */
    NO_GROUP,

    /** 이미 처리됨(랭킹 확정 · 08:00 전 확정 그룹의 1차). */
    SKIPPED,

    /** 3번 문제 푸는 그룹원이 있어 08:00:03 까지 미룸. */
    PENDING,

    /** 1차 확정(`FAILED_DEADLINE` · 잠정 랭킹). */
    DECIDED,

    /** 확정과 랭킹 확정을 한 번에(2차 · 08:00:03 뒤 처리). */
    DECIDED_FINAL,

    /** 결과는 그대로 두고 랭킹만 확정. */
    FINALIZED,
}

/** 그룹 하나의 마감 처리 결과 — [version] 은 끝난 뒤 그룹 Hash `version`(`EARLY` · `NO_GROUP` 은 0). */
data class DeadlineOutcome(val code: DeadlineCode, val version: Long)

/** 처리하지 못한 그룹 하나 — [reason] 은 마지막 오류(또는 `EARLY` 를 끝까지 받음). */
data class FailedGroup(val gid: GroupId, val reason: String)

/**
 * 한 단계(`runPhase`)의 보고(LLD deadline-result §3.2 6) — 로그 `deadline.phase_done` 의 재료.
 * [counts] 는 마지막 반환 코드별 그룹 수(`EARLY` 는 없다 — 끝까지 `EARLY` 면 [failedGroups] 다).
 */
data class DeadlineReport(
    val date: LocalDate,
    val phase: DeadlinePhase,
    /** 이 인스턴스가 맡은 버킷 수. */
    val buckets: Int,
    val counts: Map<DeadlineCode, Int>,
    val failedGroups: List<FailedGroup>,
    /** `SSCAN` 이 재시도 뒤에도 실패한 버킷 — 그 버킷의 나머지 그룹은 처리하지 못했다. */
    val failedBuckets: List<Int>,
    val elapsed: Duration,
) {
    /** 처리를 시도한 그룹 수(실패 포함). */
    val groups: Int get() = counts.values.sum() + failedGroups.size

    fun count(code: DeadlineCode): Int = counts[code] ?: 0
}
