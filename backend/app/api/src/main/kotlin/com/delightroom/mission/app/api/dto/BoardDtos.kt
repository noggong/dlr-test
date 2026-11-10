package com.delightroom.mission.app.api.dto

import java.time.Instant
import java.time.LocalDate

data class BoardEntryResponse(
    val userId: String,
    /** 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val progress: Int,
    val status: String,
    val rank: Int,
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
