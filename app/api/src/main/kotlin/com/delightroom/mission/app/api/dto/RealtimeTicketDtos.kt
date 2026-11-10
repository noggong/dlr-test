package com.delightroom.mission.app.api.dto

import java.time.Instant

/** SSE 연결용 단기 1회용 티켓. 실시간 앱의 `?ticket=` 으로 넘긴다. */
data class RealtimeTicketResponse(
    val ticket: String,
    val expiresAt: Instant,
)
