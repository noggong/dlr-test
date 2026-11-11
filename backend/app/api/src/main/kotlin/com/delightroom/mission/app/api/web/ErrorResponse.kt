package com.delightroom.mission.app.api.web

/** 모든 오류 응답의 본문. [code] 는 ErrorCode 이름이다. 필드를 바꾸면 AccessTokenWebFilter 의 401 본문도 같이 고친다. */
data class ErrorResponse(
    val code: String,
    val message: String,
)
