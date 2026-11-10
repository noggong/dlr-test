package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.RealtimeTicketResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.RealtimeService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** SSE 연결용 티켓 발급(realtime-board SPEC §4) — EventSource 는 헤더 · 다른 출처 쿠키를 못 싣는다. */
@RestController
@RequestMapping("/api/v1/realtime")
class RealtimeTicketController(
    private val realtimeService: RealtimeService,
) {
    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun issueTicket(): RealtimeTicketResponse {
        // TODO(realtime-board): realtimeService.issueTicket
        throw NotImplementedYet("realtime-board")
    }
}
