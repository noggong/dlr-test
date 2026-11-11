package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant
import java.time.LocalDate

/**
 * 한 유저의 하루 진행. 진행도 -1(시작 전) ~ 3, 줄어들지 않는다(prd §2).
 * 풀이 · 패스 · 부정 실패 판정은 Lua 한 벌만 둔다(LLD solve-signal §3) — 이 클래스에는 판정 메서드가 없다.
 */
class MissionProgress(
    val userId: UserId,
    val date: LocalDate,
    val progress: Int,
    /** SUCCEEDED · CHEAT_FAILED 는 「완료」, FAILED 는 마감 때 끝내지 못함(prd §2 · §4.1 · D17). */
    val status: MemberStatus,
    val startedAt: Instant?,
    val lastSolvedAt: Instant?,
    val passedAtQuestionNo: Int?,
) {
    /** 미션 시작 — 07:00 이후 문제은행을 처음 받은 순간(R-B1). */
    fun start(now: Instant): MissionProgress = throw NotImplementedYet("game-session")

    companion object {
        const val NOT_STARTED_PROGRESS = -1
    }
}
