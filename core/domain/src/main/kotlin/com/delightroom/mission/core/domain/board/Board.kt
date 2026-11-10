package com.delightroom.mission.core.domain.board

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.user.LoginId
import java.time.Instant
import java.time.LocalDate

/** 유저에게 보이는 상태 — 성공 · 실패 · 진행 중뿐(prd §2). */
enum class MemberStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

/** 그룹 상태(prd §4.2). [NO_RESULT] 는 그날 그룹원이 1명인 그룹(R-G6). */
enum class GroupStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    NO_RESULT,
}

class BoardEntry(
    val loginId: LoginId,
    /** 현황판 표기 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val displayProgress: Int,
    val status: MemberStatus,
    val rank: Int,
)

/** 그날의 그룹 현황판(prd §2 현황판 · §5.8). */
class Board(
    val groupId: GroupId,
    val date: LocalDate,
    val groupStatus: GroupStatus,
    val entries: List<BoardEntry>,
) {
    companion object {
        /** 랭킹 규칙(prd §6)으로 정렬한 현황판을 만든다. 부정 실패 유저 자리는 Q4. */
        fun rank(
            groupId: GroupId,
            date: LocalDate,
            members: Map<LoginId, MissionProgress>,
            gameStartAt: Instant,
        ): Board = throw NotImplementedYet("deadline-result")
    }
}

/** 확정된 그룹 결과. 성공 · 실패는 정확히 한 번 기록한다(NFR-2). */
class GroupResult(
    val groupId: GroupId,
    val date: LocalDate,
    val status: GroupStatus,
    val decidedAt: Instant,
    val board: Board,
)
