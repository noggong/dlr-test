package com.delightroom.mission.core.domain.board

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.LoginId
import java.time.Instant
import java.time.LocalDate

/**
 * 그룹원 상태(api-guide §2 · prd D17) — 그룹 Hash `<uid>:st` · 현황판 · 결과 · SSE 에 그대로 나가는 값. 그룹원 상태의 유일한 enum 이다.
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

/** 현황판 한 줄 — MemberEntry(LLD realtime-board §2.4 · SPEC §2). 보는 사람별 규칙을 적용한 뒤의 값이다. */
data class BoardEntry(
    /** 로그인 아이디(prd D5). */
    val userId: LoginId,
    /** 경쟁 순위(1, 1, 3). 보는 사람과 상관없다. */
    val rank: Int,
    /** 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val progress: Int,
    val status: MemberStatus,
    /** 미션 시작 여부(진행도 ≥ 0) — false 면 「미접속」(D13). */
    val started: Boolean,
    /** 마지막 풀이 신호의 서버 도착 시각(µs), 푼 문제가 없으면 null. */
    val lastSolvedAt: Instant?,
    /** 패스를 쓴 문제 번호, 안 썼으면 null. */
    val passedAtQuestionNo: Int?,
)

/** 그날의 그룹 현황판(prd §2 현황판 · §5.8). [entries] 는 rank 오름차순, 같으면 userId 바이트 순(RB11). 만드는 길은 [BoardView]. */
data class Board(
    val date: LocalDate,
    val groupStatus: GroupStatus,
    /** 그룹 Hash `version` — SSE `id:` 와 같은 축(RB6). */
    val version: Long,
    val entries: List<BoardEntry>,
)

/** 확정된 그룹 결과. 성공 · 실패는 정확히 한 번 기록한다(NFR-2). */
class GroupResult(
    val groupId: GroupId,
    val date: LocalDate,
    val status: GroupStatus,
    val decidedAt: Instant,
    val board: Board,
)
