package com.delightroom.mission.app.realtime.web

/** 연결 전에 실패한 요청의 본문. api 앱과 같은 모양이다. */
data class ErrorResponse(
    val code: String,
    val message: String,
)
