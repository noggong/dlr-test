package com.delightroom.mission.app.api.dto

import java.time.OffsetDateTime

/** `POST /api/v1/realtime/tickets` `201` — SSE 연결용 30초 1회용 티켓. realtime 앱의 `?ticket=` 으로 넘긴다(RB4). */
data class RealtimeTicketResponse(
    /** 32바이트 base64url(패딩 없음) 43자. */
    val ticket: String,
    /** 발급 시각(Redis) + 30초. */
    val expiresAt: OffsetDateTime,
)
