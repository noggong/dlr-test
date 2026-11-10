package com.delightroom.mission.core.domain.game

import com.delightroom.mission.common.error.NotImplementedYet
import java.time.Instant
import java.time.LocalDate

/** 하루 타임라인의 구간(prd §3). */
enum class GamePhase {
    BEFORE_START,
    OPEN,
    SUBMIT_GRACE,
    CLOSED,
}

/** 한 날짜의 게임. 시각은 호출자가 Clock 에서 얻어 넘긴다. */
class GameDay(
    val date: LocalDate,
    val startAt: Instant,
    val deadlineAt: Instant,
    val graceEndsAt: Instant,
) {
    fun phaseAt(now: Instant): GamePhase = throw NotImplementedYet("deadline-result")

    /** 마감 직전 제출 가드(D1): 도착 시각과 클릭 시각으로 풀이 신호를 받을지 정한다(R-D2). */
    fun acceptsSolve(arrivedAt: Instant, clickedAt: Instant): Boolean = throw NotImplementedYet("deadline-result")
}
