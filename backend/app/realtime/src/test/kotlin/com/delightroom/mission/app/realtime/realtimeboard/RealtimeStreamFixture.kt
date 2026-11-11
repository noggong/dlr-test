package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.event.GroupChannelType
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.infra.redis.GroupChannelCodec
import com.delightroom.mission.infra.redis.RedisKeys
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * [DeliveryLatencyTest] · [ConnectionMetricsTest] 가 같이 쓰는 SSE 픽스처 — [EventStreamTest] 의 그룹 · 티켓 · 발행 · 프레임 받기와 같은 방식이다.
 * 그룹은 SPEC 예시 4명 그룹이고 gid 는 무작위라 다른 테스트와 겹치지 않는다. 다 쓰면 [close].
 */
class RealtimeStreamFixture(
    port: Int,
    private val tickets: RealtimeTicketRepository,
    private val keys: RedisKeys,
    private val codec: GroupChannelCodec,
    connectionFactory: ReactiveRedisConnectionFactory,
) : AutoCloseable {
    companion object {
        const val EVENTS = "/realtime/v1/games/today/events"
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        /** 그룹원 uid → 로그인 아이디(SPEC 예시). */
        val MEMBERS = linkedMapOf(
            BoardFixtures.JUNHO to "junho",
            BoardFixtures.MINJI to "minji",
            BoardFixtures.DOHYUN to "dohyun",
            BoardFixtures.HANA to "hana",
        )
        private val FRAMES = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }

    private val connection: StatefulRedisConnection<String, String> =
        ((connectionFactory as LettuceConnectionFactory).requiredNativeClient as RedisClient).connect()
    val redis = connection.sync()
    private val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(TIMEOUT).build()

    val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))

    /** 명단 Hash(`#size` · uid → 로그인 아이디) + 그룹 Hash(진행 중 · version = [BoardFixtures.VERSION]). */
    fun group() {
        redis.hset(keys.members(gid, DATE), linkedMapOf("#size" to "4", "#name" to "g$gid") + MEMBERS.mapKeys { "${it.key}" })
        redis.hset(keys.group(gid, DATE), BoardFixtures.inProgressHash())
    }

    /** 발급 API 와 같은 값 · TTL 로 티켓을 넣는다. */
    private fun ticket(uid: Long): String = runBlocking {
        val value = RealtimeTicket.newValue(SecureRandom())
        check(tickets.saveNew(RealtimeTicket(value, UserId(uid), gid, DATE, Instant.now().plus(RealtimeTicket.TTL)), RealtimeTicket.TTL))
        value
    }

    /** [uid] 의 티켓으로 연결해 스트림을 연 채 둔다(첫 프레임은 아직 기다리지 않는다). */
    fun open(uid: Long): Frames {
        val body = client.get()
            .uri { it.path(EVENTS).queryParam("ticket", ticket(uid)).build() }
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(FRAMES).responseBody
        return Frames(body)
    }

    /** `solve.applied`(바뀐 그룹원 junho) 메시지 하나를 `SPUBLISH` 한다 — 그룹 Hash 는 진행 중 Hash 에 [version] 을 덮은 것. */
    fun publish(version: Long) {
        val raw = codec.encode(
            GroupChannelType.SOLVE_APPLIED, gid, DATE, "$version-0", UserId(BoardFixtures.JUNHO), version,
            BoardFixtures.micros(LocalTime.parse("07:31:02.004118")),
            BoardFixtures.inProgressHash() + ("version" to "$version"),
        )
        redis.spublish(keys.channel(gid), raw)
    }

    override fun close() {
        redis.del(keys.group(gid, DATE), keys.members(gid, DATE))
        connection.close()
    }

    /** 받은 프레임 하나와 클라이언트가 그것을 받은 순간의 `System.nanoTime`. */
    data class Received(val frame: ServerSentEvent<String>, val nanos: Long)

    /** 스트림을 열어 둔 채 프레임을 받은 시각과 함께 쌓는다. 끝나면(정상 · 오류) [ended] 가 풀린다. */
    class Frames(flux: Flux<ServerSentEvent<String>>) {
        private val queue = LinkedBlockingQueue<Received>()
        private val ended = CountDownLatch(1)

        @Volatile var error: Throwable? = null
        private val subscription = flux.subscribe(
            { queue.add(Received(it, System.nanoTime())) },
            { error = it; ended.countDown() },
            { ended.countDown() },
        )

        fun next(wait: Duration = TIMEOUT): Received =
            checkNotNull(queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS)) { "${wait.toMillis()}ms 안에 프레임이 오지 않음" }

        fun isEnded(): Boolean = ended.count == 0L

        fun close() = subscription.dispose()
    }
}
