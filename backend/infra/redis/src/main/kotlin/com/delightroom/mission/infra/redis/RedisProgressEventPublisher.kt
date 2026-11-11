package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.port.ProgressEventPublisher
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/** 그룹 채널로 Pub/Sub 발행. */
internal class RedisProgressEventPublisher(
    private val redis: ReactiveStringRedisTemplate,
) : ProgressEventPublisher {
    override suspend fun publish(message: GroupChannelMessage): Unit = TODO("realtime-board")
}
