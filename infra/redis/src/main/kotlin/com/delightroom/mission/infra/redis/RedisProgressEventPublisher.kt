package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.event.GroupEvent
import com.delightroom.mission.core.port.ProgressEventPublisher
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/** 그룹 채널로 Pub/Sub 발행. */
internal class RedisProgressEventPublisher(
    private val redis: ReactiveStringRedisTemplate,
) : ProgressEventPublisher {
    override suspend fun publish(event: GroupEvent): Unit = TODO("realtime-board")
}
