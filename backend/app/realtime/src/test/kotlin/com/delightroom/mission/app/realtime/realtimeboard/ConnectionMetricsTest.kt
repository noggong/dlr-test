package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.app.realtime.sse.SseConnectionMetrics
import com.delightroom.mission.app.realtime.sse.SseConnectionRegistry
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.infra.redis.GroupChannelCodec
import com.delightroom.mission.infra.redis.RedisKeys
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * ORACLE realtime-board O31 — 게이지 `realtime.connections` 가 이 노드의 SSE 연결 수를 따라간다(붙으면 오르고 끊으면 내린다).
 * 앱이 세운 `MeterRegistry` 에서 읽는다. 끊김은 서버에 비동기로 닿으므로 5초까지 기다린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MutableClockConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionMetricsTest
    @Autowired
    constructor(
        @param:Value("\${local.server.port}") port: Int,
        tickets: RealtimeTicketRepository,
        keys: RedisKeys,
        codec: GroupChannelCodec,
        connectionFactory: ReactiveRedisConnectionFactory,
        private val meters: MeterRegistry,
        private val registry: SseConnectionRegistry,
    ) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun redis(registry: DynamicPropertyRegistry) = RealtimeTestRedis.register(registry)
        }

        private val fixture = RealtimeStreamFixture(port, tickets, keys, codec, connectionFactory)

        @AfterAll
        fun close() = fixture.close()

        private fun gauge(): Double = meters.get(SseConnectionMetrics.NAME).gauge().value()

        /** 게이지가 [expected] 가 될 때까지(5초) 기다리고, 그때 레지스트리 전체 연결 수와 같은지 본다. */
        private fun awaitGauge(expected: Int) {
            val until = System.nanoTime() + RealtimeStreamFixture.TIMEOUT.toNanos()
            while (gauge() != expected.toDouble()) {
                check(System.nanoTime() < until) { "5초 안에 게이지가 $expected 가 되지 않음: ${gauge()}" }
                Thread.sleep(20)
            }
            assertThat(gauge()).isEqualTo(registry.connectionCount().toDouble())
        }

        @Test
        fun `O31 게이지 realtime_connections 는 태그 없이 연결 수를 따라간다 — B · B+3 · B+2 · B`() {
            fixture.group()
            val gauge = meters.get(SseConnectionMetrics.NAME).gauge()
            assertThat(gauge.id.tags).isEmpty()
            assertThat(gauge.id.baseUnit).isEqualTo("connections")

            val base = registry.connectionCount()
            awaitGauge(base)

            val streams = listOf(BoardFixtures.JUNHO, BoardFixtures.MINJI, BoardFixtures.DOHYUN).map { fixture.open(it) }
            // snapshot 을 받았으면 서버는 이미 attach(= 등록)했다
            streams.forEach { assertThat(it.next().frame.event()).isEqualTo("board.snapshot") }
            awaitGauge(base + 3)

            streams.first().close()
            awaitGauge(base + 2)

            streams.drop(1).forEach { it.close() }
            awaitGauge(base)
            assertThat(registry.connectionCount(fixture.gid)).isZero()
        }
    }
