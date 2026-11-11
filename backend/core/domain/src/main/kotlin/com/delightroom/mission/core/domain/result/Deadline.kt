package com.delightroom.mission.core.domain.result

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
