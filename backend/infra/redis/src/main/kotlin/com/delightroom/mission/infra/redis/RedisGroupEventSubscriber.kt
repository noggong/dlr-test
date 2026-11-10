package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.event.GroupEvent
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.GroupEventSubscriber
import kotlinx.coroutines.flow.Flow
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory

/**
 * 그룹 채널 Pub/Sub 구독. ReactiveRedisMessageListenerContainer 는 만들 때 바로 연결하므로
 * 빈으로 두지 않고 첫 구독 때 만든다 — Redis 없이도 컨텍스트가 뜨게 한다.
 */
internal class RedisGroupEventSubscriber(
    private val connectionFactory: ReactiveRedisConnectionFactory,
) : GroupEventSubscriber {
    override fun subscribe(groupId: GroupId): Flow<GroupEvent> = TODO("realtime-board")
}
