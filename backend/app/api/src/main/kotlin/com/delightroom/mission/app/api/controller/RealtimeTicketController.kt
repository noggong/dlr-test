package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.auth.AuthPrincipal
import com.delightroom.mission.app.api.auth.CurrentUser
import com.delightroom.mission.app.api.dto.RealtimeTicketResponse
import com.delightroom.mission.core.config.GameProperties
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
    private val gameProperties: GameProperties,
) {
    /** `201 { ticket, expiresAt }` — 거부는 `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP`(RB2 · RB3). 본문은 읽지 않는다. */
    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun issueTicket(@CurrentUser user: AuthPrincipal): RealtimeTicketResponse {
        val ticket = realtimeService.issueTicket(user.userId, user.groupId)
        return RealtimeTicketResponse(ticket.value, ticket.expiresAt.atZone(gameProperties.zone).toOffsetDateTime())
    }
}
