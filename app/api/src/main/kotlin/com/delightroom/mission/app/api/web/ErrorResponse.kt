package com.delightroom.mission.app.api.web

/** 모든 오류 응답의 본문. [code] 는 ErrorCode 이름이다. */
data class ErrorResponse(
    val code: String,
    val message: String,
)
