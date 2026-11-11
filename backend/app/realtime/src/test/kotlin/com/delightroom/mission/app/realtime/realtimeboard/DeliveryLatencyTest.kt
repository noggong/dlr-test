package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.app.realtime.sse.SseConnectionRegistry
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.infra.redis.GroupChannelCodec
import com.delightroom.mission.infra.redis.RedisKeys
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
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import kotlin.math.ceil

/**
 * ORACLE realtime-board O25(+ I2 · I5 를 같은 수신 기록으로) — 한 그룹 10연결에 `SPUBLISH` 1,000회(10ms 간격)를 보내고
 * 발행 직전 → 클라이언트 프레임 수신까지의 지연을 잰다. 측정 방법은 ORACLE O25 에 적었다.
 * 실제 서버 포트로 붙으므로 서버의 현황판 만들기 · 직렬화 · 프레임 쓰기와 로컬 TCP 가 지연에 들어간다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MutableClockConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryLatencyTest
    @Autowired
    constructor(
        @param:Value("\${local.server.port}") port: Int,
        tickets: RealtimeTicketRepository,
        keys: RedisKeys,
        codec: GroupChannelCodec,
        connectionFactory: ReactiveRedisConnectionFactory,
        private val registry: SseConnectionRegistry,
    ) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun redis(registry: DynamicPropertyRegistry) = RealtimeTestRedis.register(registry)

            private const val CONNECTIONS = 10
            private const val MESSAGES = 1_000
            private const val INTERVAL_MS = 10L
            private val P99_LIMIT: Duration = Duration.ofMillis(200)
        }

        private val fixture = RealtimeStreamFixture(port, tickets, keys, codec, connectionFactory)
        private val json = jacksonObjectMapper()

        @AfterAll
        fun close() = fixture.close()

        @Test
        fun `O25 한 그룹 10연결 · 1,000회 발행 — 10,000건 모두 받고 p99 200ms 이하`() {
            fixture.group()
            // 그룹원 4명의 티켓을 돌려 쓴다(같은 유저 여러 탭 — 따로 센다)
            val viewers = (0 until CONNECTIONS).map { RealtimeStreamFixture.MEMBERS.entries.elementAt(it % RealtimeStreamFixture.MEMBERS.size) }
            val streams = viewers.map { fixture.open(it.key) }
            // 모두 snapshot 을 받은 뒤 발행한다 — 그 뒤 발행은 반드시 각 연결에 온다(RB5)
            streams.forEach { assertThat(it.next().frame.event()).isEqualTo("board.snapshot") }
            assertThat(registry.connectionCount(fixture.gid)).isEqualTo(CONNECTIONS)

            val v0 = BoardFixtures.VERSION
            val publishedAt = LongArray(MESSAGES + 1)
            for (i in 1..MESSAGES) {
                publishedAt[i] = System.nanoTime()
                fixture.publish(v0 + i)
                Thread.sleep(INTERVAL_MS)
            }

            // 연결마다 1,000 프레임을 받을 때까지(마지막 발행 뒤 5초까지) — 모자라면 유실
            val received = streams.map { frames ->
                buildList {
                    while (size < MESSAGES) {
                        val r = runCatching { frames.next() }.getOrNull() ?: break
                        if (r.frame.event() != null) add(r) // 하트비트 주석은 세지 않는다
                    }
                }
            }
            streams.forEach { it.close() }

            val latencies = mutableListOf<Long>()
            received.forEachIndexed { c, frames ->
                val viewer = viewers[c].value
                // 유실 0 · I2 — id 가 V0+1 ~ V0+1000 을 빠짐 · 겹침 없이 차례로
                assertThat(frames.map { it.frame.id()!!.toLong() }).describedAs("연결 $c($viewer) 수신 id")
                    .containsExactlyElementsOf((1..MESSAGES).map { v0 + it })
                frames.forEach { r ->
                    assertThat(r.frame.event()).isEqualTo("member.progressed")
                    assertNoForeignCheatFailed(r.frame.data()!!, viewer)
                    latencies += r.nanos - publishedAt[(r.frame.id()!!.toLong() - v0).toInt()]
                }
            }
            assertThat(latencies).hasSize(CONNECTIONS * MESSAGES)

            val sorted = latencies.sorted()
            fun pct(p: Double): Duration = Duration.ofNanos(sorted[ceil(p * sorted.size).toInt() - 1])
            val p50 = pct(0.50)
            val p99 = pct(0.99)
            println(
                "[O25] ${sorted.size}건 수신 · p50 ${ms(p50)}ms · p99 ${ms(p99)}ms · 최대 ${ms(Duration.ofNanos(sorted.last()))}ms " +
                    "(연결 $CONNECTIONS · 발행 $MESSAGES · 간격 ${INTERVAL_MS}ms)",
            )
            assertThat(p99).describedAs("p99").isLessThanOrEqualTo(P99_LIMIT)
        }

        /** I5 — 결과 없는 그룹 프레임에서 `CHEAT_FAILED` 줄은 보는 사람 본인뿐. */
        @Suppress("UNCHECKED_CAST")
        private fun assertNoForeignCheatFailed(data: String, viewer: String) {
            val parsed: Map<String, Any?> = json.readValue(data)
            assertThat(parsed["groupStatus"]).isEqualTo("IN_PROGRESS")
            val cheat = (parsed["entries"] as List<Map<String, Any?>>).filter { it["status"] == "CHEAT_FAILED" }.map { it["userId"] }
            assertThat(cheat).isSubsetOf(viewer)
        }

        private fun ms(d: Duration): String = "%.2f".format(d.toNanos() / 1_000_000.0)
    }
