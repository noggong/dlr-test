package com.delightroom.mission.core.domain.board

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.user.LoginId
import java.time.Instant
import java.time.LocalDate

/**
 * 그룹원 상태(api-guide §2 · prd D17) — 현황판 · 결과 · SSE 에 그대로 나가는 값.
 * 결과 확정 전 다른 그룹원의 부정 실패는 IN_PROGRESS 로 보인다(보는 사람별 규칙 — realtime-board RB10 · D2).
 */
enum class MemberStatus {
    /** 그날 미션을 시작하지 않음(진행도 -1) — 「미접속」. */
    NOT_STARTED,

    /** 진행 중(진행도 0~2). */
    IN_PROGRESS,

    /** 3문제 완료. */
    SUCCEEDED,

    /** 부정 실패(로컬 스토리지 빔 — R-F2). */
    CHEAT_FAILED,

    /** 마감 때 끝내지 못함(진행 중 · 미접속이었음). */
    FAILED,
}

/** 그룹 상태(api-guide §2). 1명 그룹은 게임이 열리지 않아 결과가 없다(NO_RESULT_YET — deadline-result DR12). */
enum class GroupStatus {
    /** 게임 중 · 결과 미확정. */
    IN_PROGRESS,

    /** 08:00 이후 결과 확정 대기(08:00:03 까지 미룸 · worker: deadline 처리 전) — 「로딩 중」. */
    PENDING,

    SUCCEEDED,
    FAILED,
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
        /** 랭킹 규칙(prd §6)으로 정렬한 현황판을 만든다. 부정 실패 유저는 확정 전에도 맨 아래(prd D2). */
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
