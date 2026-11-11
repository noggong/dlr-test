package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant
import java.time.LocalDate

/** 풀이 신호(R-S1). [clickedAt] 은 마감 가드(D1)에, 랭킹에는 서버 도착 시각을 쓴다(R-S5). */
data class SolveSignal(
    val questionNo: Int,
    val questionId: QuestionId,
    val successTime: Instant,
    val clickedAt: Instant,
)

/** 패스 신호(R-P2). */
data class PassSignal(
    val questionNo: Int,
    val questionId: QuestionId,
)

/** 풀이 신호를 반영한 결과. 재시도로 같은 신호가 다시 와도 같은 결과를 준다(R-S3 · R-S4). */
sealed interface SolveOutcome {
    data class Applied(val progress: Int) : SolveOutcome

    data class AlreadyApplied(val progress: Int) : SolveOutcome
}

/** 한 유저의 하루 진행. 진행도 -1(시작 전) ~ 3, 줄어들지 않는다(prd §2). */
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

    fun solve(signal: SolveSignal, arrivedAt: Instant): SolveOutcome = throw NotImplementedYet("solve-signal")

    fun pass(signal: PassSignal): MissionProgress = throw NotImplementedYet("solve-signal")

    /** 로컬 스토리지가 빈 유저의 부정 실패(R-F2). */
    fun failByCheat(now: Instant): MissionProgress = throw NotImplementedYet("solve-signal")

    /** 판정용 「완료」 — 미션 성공 또는 부정 실패(prd §2). */
    fun isFinished(): Boolean = throw NotImplementedYet("solve-signal")

    companion object {
        const val NOT_STARTED_PROGRESS = -1
    }
}
