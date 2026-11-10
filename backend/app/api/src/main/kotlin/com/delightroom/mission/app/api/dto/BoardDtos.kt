package com.delightroom.mission.app.api.dto

import java.time.Instant
import java.time.LocalDate

data class BoardEntryResponse(
    /** 그룹원 ID = 로그인 아이디(prd D5). */
    val userId: String,
    /** 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val progress: Int,
    val status: String,
    val rank: Int,
    /** 미션 시작 여부 — false 면 진행도 -1(표기 0)(prd D5). */
    val started: Boolean? = null,
    /** 마지막 풀이 신호의 서버 도착 시각, 푼 문제가 없으면 null(R-S5 · prd D5). */
    val lastSolvedAt: Instant? = null,
    /** 패스를 쓴 문제 번호, 안 썼으면 null(R-P4 · prd D5). */
    val passedAtQuestionNo: Int? = null,
    /** 부정 실패 여부 — 다른 그룹원에게는 그룹 결과 확정 뒤에만 true(prd D2 · D5). */
    val cheatFailed: Boolean? = null,
)

/** 현황판(prd §2 · R-R4). */
data class BoardResponse(
    val date: LocalDate,
    val groupStatus: String,
    val entries: List<BoardEntryResponse>,
)

/** 결과 조회(R-V1 · R-V2). 1명 그룹이면 groupStatus = NO_RESULT, ranking 은 비어 있다(R-V4). */
data class ResultResponse(
    val date: LocalDate,
    val groupStatus: String,
    val decidedAt: Instant?,
    val myRank: Int?,
    val ranking: List<BoardEntryResponse>,
    val nextGameStartAt: Instant,
)
