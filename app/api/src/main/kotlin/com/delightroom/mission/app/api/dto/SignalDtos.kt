package com.delightroom.mission.app.api.dto

import java.time.Instant

/** 풀이 신호(R-S1 · R-D2). */
data class SolveRequest(
    val questionNo: Int,
    val questionId: String,
    val successTime: Instant,
    val clickedAt: Instant,
)

data class SolveResponse(
    val progress: Int,
    val missionStatus: String,
    val groupStatus: String,
)

/** 패스 신호(R-P2). */
data class PassRequest(
    val questionNo: Int,
    val questionId: String,
)

data class PassResponse(
    val passedAtQuestionNo: Int,
)

/** 부정 실패 처리 결과(R-F2). */
data class FailureResponse(
    val progress: Int,
    val missionStatus: String,
    val groupStatus: String,
)
