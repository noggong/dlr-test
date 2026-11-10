package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.auth.RealtimeTicket

interface RealtimeTicketRepository {
    suspend fun save(ticket: RealtimeTicket)

    /** 1회용 — 꺼내면서 지운다. */
    suspend fun take(value: String): RealtimeTicket?
}
