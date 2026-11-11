package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.port.DemoClockStore
import com.delightroom.mission.core.port.EventStreamConsumer
import com.delightroom.mission.core.port.GroupBoardRepository
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.port.GroupEventSubscriber
import com.delightroom.mission.core.port.ProgressEventPublisher
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.port.RefreshTokenRepository
import com.delightroom.mission.core.port.ResultCache
import com.delightroom.mission.core.port.RosterRepository
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
    fun groupDayRepository(
        redis: ReactiveStringRedisTemplate,
        scripts: RedisScripts,
        keys: RedisKeys,
        properties: GameProperties,
    ): GroupDayRepository = RedisGroupDayRepository(redis, scripts, keys, properties)

    @Bean
    fun resultCache(
        redis: ReactiveStringRedisTemplate,
        scripts: RedisScripts,
        keys: RedisKeys,
        properties: GameProperties,
    ): ResultCache = RedisResultCache(redis, scripts, keys, properties)

    @Bean
    fun groupBoardRepository(scripts: RedisScripts, keys: RedisKeys, properties: GameProperties): GroupBoardRepository =
        RedisGroupBoardRepository(scripts, keys, properties)

    @Bean
    fun progressRepository(
        redis: ReactiveStringRedisTemplate,
        scripts: RedisScripts,
        keys: RedisKeys,
        gameClock: GameClock,
        properties: GameProperties,
    ): ProgressRepository = RedisProgressRepository(redis, scripts, keys, gameClock, properties)

    @Bean
    fun rosterRepository(
        redis: ReactiveStringRedisTemplate,
        scripts: RedisScripts,
        keys: RedisKeys,
        gameClock: GameClock,
    ): RosterRepository = RedisRosterRepository(redis, scripts, keys, gameClock)

    @Bean
    fun demoClockStore(redis: ReactiveStringRedisTemplate, keys: RedisKeys): DemoClockStore = RedisDemoClockStore(redis, keys)

    @Bean
    fun progressEventPublisher(redis: ReactiveStringRedisTemplate): ProgressEventPublisher =
        RedisProgressEventPublisher(redis)

    @Bean
    fun realtimeTicketRepository(redis: ReactiveStringRedisTemplate, keys: RedisKeys): RealtimeTicketRepository =
        RedisRealtimeTicketRepository(redis, keys)

    @Bean
    fun refreshTokenRepository(
        redis: ReactiveStringRedisTemplate,
        scripts: RedisScripts,
        keys: RedisKeys,
    ): RefreshTokenRepository = RedisRefreshTokenRepository(redis, scripts, keys)

    @Bean
    fun questionBankCodec(): QuestionBankCodec = QuestionBankCodec()

    @Bean
    fun questionBankCache(
        redis: ReactiveStringRedisTemplate,
        keys: RedisKeys,
        codec: QuestionBankCodec,
        gameClock: GameClock,
    ): QuestionBankCache = RedisQuestionBankCache(redis, keys, codec, gameClock)

    @Bean
    fun eventStreamConsumer(scripts: RedisScripts, keys: RedisKeys): EventStreamConsumer =
        RedisEventStreamConsumer(scripts, keys)

    @Bean
    fun groupChannelCodec(): GroupChannelCodec = GroupChannelCodec()

    /** 첫 구독 때 연결한다 — api 앱 기동은 Redis 구독 연결을 열지 않는다. */
    @Bean
    fun groupEventSubscriber(
        connectionFactory: ReactiveRedisConnectionFactory,
        keys: RedisKeys,
        codec: GroupChannelCodec,
    ): GroupEventSubscriber = RedisGroupEventSubscriber(connectionFactory, keys, codec)
}
