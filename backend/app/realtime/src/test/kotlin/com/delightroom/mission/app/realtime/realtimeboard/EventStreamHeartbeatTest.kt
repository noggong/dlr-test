package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * ORACLE realtime-board O26 — 마지막 프레임 뒤 `realtime.heartbeat` 마다 주석 `: ping`(RB9 · SPEC §6.6).
 * 15초를 기다리지 않도록 1초로 덮는다 — 설정이 달라 [EventStreamTest] 와 다른 컨텍스트다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["realtime.heartbeat=1s"])
@Import(MutableClockConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventStreamHeartbeatTest
    @Autowired
    constructor(
        @param:Value("\${local.server.port}") private val port: Int,
        private val tickets: RealtimeTicketRepository,
        private val keys: RedisKeys,
        connectionFactory: ReactiveRedisConnectionFactory,
    ) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun redis(registry: DynamicPropertyRegistry) = RealtimeTestRedis.register(registry)

            private val FRAMES = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
        }

        private val connection: StatefulRedisConnection<String, String> =
            ((connectionFactory as LettuceConnectionFactory).requiredNativeClient as RedisClient).connect()
        private val redis = connection.sync()
        private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))

        @AfterAll
        fun close() {
            redis.del(keys.group(gid, DATE), keys.members(gid, DATE))
            connection.close()
        }

        @Test
        fun `O26 하트비트 1초로 3·5초 동안 ping 주석 3개(±1), id · event 없음`() {
            redis.hset(keys.members(gid, DATE), mapOf("#size" to "4", "${BoardFixtures.MINJI}" to "minji"))
            redis.hset(keys.group(gid, DATE), BoardFixtures.inProgressHash())
            val value = RealtimeTicket.newValue(SecureRandom())
            runBlocking {
                check(tickets.saveNew(RealtimeTicket(value, UserId(BoardFixtures.MINJI), gid, DATE, Instant.now().plus(RealtimeTicket.TTL)), RealtimeTicket.TTL))
            }

            val frames = WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(Duration.ofSeconds(5)).build()
                .get().uri { it.path("/realtime/v1/games/today/events").queryParam("ticket", value).build() }
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk
                .returnResult(FRAMES).responseBody
                .take(Duration.ofMillis(3_500)).collectList().block(Duration.ofSeconds(10))!!

            assertThat(frames.first().event()).isEqualTo("board.snapshot")
            val pings = frames.drop(1)
            assertThat(pings).allSatisfy {
                assertThat(it.comment()).isEqualTo("ping")
                assertThat(it.id()).isNull()
                assertThat(it.event()).isNull()
                assertThat(it.data()).isNull()
            }
            assertThat(pings.size).isBetween(2, 4)
        }
    }
