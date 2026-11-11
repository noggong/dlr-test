package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.auth.RealtimeTicket
import java.time.Duration

/** SSE 티켓 `tk:{<ticket>}`(LLD realtime-board §2.5). */
interface RealtimeTicketRepository {
    /** 같은 값이 없을 때만 [ttl] 동안 저장한다(`SET NX EX`). 이미 있으면 `false`. */
    suspend fun saveNew(ticket: RealtimeTicket, ttl: Duration): Boolean

    /** 1회용 — 꺼내면서 지운다(`GETDEL`). 없거나 만료 · 이미 꺼냈으면 `null`. */
    suspend fun take(value: String): RealtimeTicket?
}
