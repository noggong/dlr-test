package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.port.RealtimeTicketRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

internal class RedisRealtimeTicketRepository(
    private val redis: ReactiveStringRedisTemplate,
) : RealtimeTicketRepository {
    override suspend fun save(ticket: RealtimeTicket): Unit = TODO("realtime-board")

    override suspend fun take(value: String): RealtimeTicket? = TODO("realtime-board")
}
