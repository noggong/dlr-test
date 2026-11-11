package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.port.GroupEventSubscriber
import com.delightroom.mission.core.port.ProgressEventPublisher
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.port.RefreshTokenRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/** core 포트의 Redis 구현을 등록한다. 구현 클래스는 internal 이라 이 모듈 밖에서 보이지 않는다. */
@AutoConfiguration(after = [DataRedisReactiveAutoConfiguration::class])
class RedisInfraAutoConfiguration {
    @Bean
    fun redisKeys(properties: GameProperties): RedisKeys = RedisKeys(properties)

    @Bean
    fun redisScripts(redis: ReactiveStringRedisTemplate): RedisScripts = RedisScripts(redis)

    @Bean
    fun progressRepository(redis: ReactiveStringRedisTemplate): ProgressRepository = RedisProgressRepository(redis)

    @Bean
    fun progressEventPublisher(redis: ReactiveStringRedisTemplate): ProgressEventPublisher =
        RedisProgressEventPublisher(redis)

    @Bean
    fun realtimeTicketRepository(redis: ReactiveStringRedisTemplate): RealtimeTicketRepository =
        RedisRealtimeTicketRepository(redis)

    @Bean
    fun refreshTokenRepository(
        redis: ReactiveStringRedisTemplate,
        scripts: RedisScripts,
        keys: RedisKeys,
    ): RefreshTokenRepository = RedisRefreshTokenRepository(redis, scripts, keys)

    @Bean
    fun groupEventSubscriber(connectionFactory: ReactiveRedisConnectionFactory): GroupEventSubscriber =
        RedisGroupEventSubscriber(connectionFactory)
}
