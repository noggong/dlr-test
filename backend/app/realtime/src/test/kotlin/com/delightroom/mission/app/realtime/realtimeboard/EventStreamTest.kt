package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.app.realtime.sse.GroupChannelHub
import com.delightroom.mission.app.realtime.sse.SseConnectionRegistry
import com.delightroom.mission.app.realtime.sse.SseConnectionReleaseFilter
import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupBoardRepository
import com.delightroom.mission.core.port.GroupEventSubscriber
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.FluxExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * ORACLE realtime-board O12 ~ O14 · O23 · O27 ~ O29(+ 본문 수집 전에 끝난 요청의 연결 반환) — `GET /realtime/v1/games/today/events?ticket=` 의 연결 · 첫 snapshot · 연결 전 오류.
 * 실제 서버 포트로 붙어 SSE 를 받는다(클라이언트가 끊으면 서버 스트림이 취소되는 것까지 본다). 그룹 Hash · 명단은 SPEC 예시 4명 그룹을
 * 직접 `HSET` 하고, 티켓은 저장소로 바로 넣는다(발급 API 는 app:api — ORACLE 테스트 규약). 모드 `stream` 은 창이 없어 시계를 옮기지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MutableClockConfiguration::class, EventStreamTest.CallOrderRecorder::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventStreamTest
    @Autowired
    constructor(
        @param:Value("\${local.server.port}") private val port: Int,
        private val tickets: RealtimeTicketRepository,
        private val keys: RedisKeys,
        private val registry: SseConnectionRegistry,
        private val hub: GroupChannelHub,
        private val releaseFilter: SseConnectionReleaseFilter,
        connectionFactory: ReactiveRedisConnectionFactory,
    ) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun redis(registry: DynamicPropertyRegistry) = RealtimeTestRedis.register(registry)

            private const val EVENTS = "/realtime/v1/games/today/events"
            private val MEMBERS = linkedMapOf(
                BoardFixtures.JUNHO to "junho",
                MINJI to "minji",
                BoardFixtures.DOHYUN to "dohyun",
                BoardFixtures.HANA to "hana",
            )
            private val FRAMES = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
            private val TIMEOUT: Duration = Duration.ofSeconds(5)

            /** 구독 확인 · `board_read` 호출을 일어난 차례대로(O29). */
            val calls = ConcurrentLinkedQueue<String>()
        }

        /**
         * O29 의 테스트 대역 — 운영 [GroupEventSubscriber] · [GroupBoardRepository] 빈을 감싸 「구독 확인」과 「`board_read`」를 [calls] 에 적는다.
         * 동작은 그대로 위임한다.
         */
        class CallOrderRecorder : BeanPostProcessor, BeanFactoryAware {
            private lateinit var beans: BeanFactory

            override fun setBeanFactory(beanFactory: BeanFactory) {
                beans = beanFactory
            }

            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                when (bean) {
                    is GroupEventSubscriber -> RecordingSubscriber(bean)
                    is GroupBoardRepository -> RecordingBoards(bean) { gid -> beans.getBean(SseConnectionRegistry::class.java).connectionCount(gid) }
                    else -> bean
                }
        }

        private class RecordingSubscriber(private val delegate: GroupEventSubscriber) : GroupEventSubscriber, DisposableBean {
            override fun subscribe(groupId: GroupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage> =
                delegate.subscribe(groupId) {
                    calls += "subscribed:${groupId.value}"
                    onSubscribed()
                }

            override fun destroy() {
                (delegate as? DisposableBean)?.destroy()
            }
        }

        /** 읽을 때 그 그룹에 붙은 연결 수도 적는다 — 열린 구독을 나눠 받는 연결도 attach(= 등록)가 읽기보다 먼저인지 본다. */
        private class RecordingBoards(
            private val delegate: GroupBoardRepository,
            private val attached: (GroupId) -> Int,
        ) : GroupBoardRepository {
            override suspend fun read(groupId: GroupId, date: LocalDate, userId: UserId, mode: BoardReadMode): BoardRead {
                calls += "read:${groupId.value}:$mode:attached=${attached(groupId)}"
                return delegate.read(groupId, date, userId, mode)
            }
        }

        private val connection: StatefulRedisConnection<String, String> =
            ((connectionFactory as LettuceConnectionFactory).requiredNativeClient as RedisClient).connect()
        private val redis = connection.sync()
        private val json = jacksonObjectMapper()
        private val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(TIMEOUT).build()

        private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
        private val groupKey get() = keys.group(gid, DATE)
        private val rosterKey get() = keys.members(gid, DATE)

        @AfterAll
        fun close() = connection.close()

        @AfterEach
        fun cleanUp() {
            redis.del(groupKey, rosterKey)
        }

        // ---- 픽스처 ----

        /** 명단 Hash(`#size` · uid → 로그인 아이디) + 그룹 Hash. */
        private fun group(hash: Map<String, String> = BoardFixtures.inProgressHash()) {
            redis.hset(rosterKey, linkedMapOf("#size" to "4", "#name" to "g$gid") + MEMBERS.mapKeys { "${it.key}" })
            redis.hset(groupKey, hash)
        }

        /** 발급 API 와 같은 값 · TTL 로 티켓을 넣는다. */
        private fun ticket(uid: Long = MINJI): String = runBlocking {
            val value = RealtimeTicket.newValue(SecureRandom())
            val ticket = RealtimeTicket(value, UserId(uid), gid, DATE, Instant.now().plus(RealtimeTicket.TTL))
            check(tickets.saveNew(ticket, RealtimeTicket.TTL))
            value
        }

        private fun connect(ticket: String?, lastEventId: String? = null): WebTestClient.ResponseSpec =
            client.get()
                .uri { b -> b.path(EVENTS).apply { if (ticket != null) queryParam("ticket", ticket) }.build() }
                .accept(MediaType.TEXT_EVENT_STREAM)
                .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
                .exchange()

        private fun stream(spec: WebTestClient.ResponseSpec): FluxExchangeResult<ServerSentEvent<String>> =
            spec.expectStatus().isOk.returnResult(FRAMES)

        /** 첫 프레임만 받고 끊는다(클라이언트 끊김 — 서버가 연결을 허브에 돌려준다). */
        private fun firstFrame(spec: WebTestClient.ResponseSpec): ServerSentEvent<String> =
            stream(spec).responseBody.take(1).blockFirst(TIMEOUT)!!

        private fun data(frame: ServerSentEvent<String>): Map<String, Any?> = json.readValue(frame.data()!!)

        private fun shardSubscribers(): Long = redis.pubsubShardNumsub(keys.channel(gid)).values.single()

        /** 연결이 모두 허브에 돌아오고 구독이 풀릴 때까지(끊김은 서버에 비동기로 닿는다). */
        private fun awaitReleased() {
            val until = System.nanoTime() + TIMEOUT.toNanos()
            while (registry.connectionCount(gid) != 0 || shardSubscribers() != 0L) {
                check(System.nanoTime() < until) { "5초 안에 풀리지 않음: 연결 ${registry.connectionCount(gid)} · 구독 ${shardSubscribers()}" }
                Thread.sleep(20)
            }
        }

        private fun assertTicketInvalid(spec: WebTestClient.ResponseSpec) {
            spec.expectStatus().isEqualTo(401)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("REALTIME_TICKET_INVALID")
        }

        // ---- 기준 ----

        @Test
        fun `O12 첫 프레임은 board_snapshot 이고 id = data_version = Hash version, data 키 5개`() {
            group()
            val result = stream(connect(ticket()))
            assertThat(result.responseHeaders.contentType.toString()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
            assertThat(result.responseHeaders.cacheControl).isEqualTo("no-cache")
            assertThat(result.responseHeaders.getFirst("X-Accel-Buffering")).isEqualTo("no")

            val first = result.responseBody.take(1).blockFirst(TIMEOUT)!!
            assertThat(first.event()).isEqualTo("board.snapshot")
            val data = data(first)
            assertThat(data.keys).containsExactly("version", "date", "groupStatus", "entries", "sentAt")
            assertThat(first.id()).isEqualTo("${BoardFixtures.VERSION}")
            assertThat(data["version"]).isEqualTo(BoardFixtures.VERSION.toInt())
            assertThat(data["date"]).isEqualTo("$DATE")
            assertThat(data["groupStatus"]).isEqualTo("IN_PROGRESS")
            assertThat(data["sentAt"] as String).matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+09:00""")

            // 클라이언트가 끊으면 연결 수 · 구독이 0 으로 돌아온다(LLD §4.3 8 b)
            awaitReleased()
        }

        @Test
        fun `O13 티켓 없음 · 모양 틀림 · 없는 티켓 · 만료 · 이미 씀은 모두 401 REALTIME_TICKET_INVALID JSON`() {
            group()
            assertTicketInvalid(connect(null))
            assertTicketInvalid(connect("abc"))
            assertTicketInvalid(connect(RealtimeTicket.newValue(SecureRandom())))

            val expired = ticket()
            redis.pexpire(keys.ticket(expired), 1)
            Thread.sleep(50)
            assertTicketInvalid(connect(expired))

            val used = ticket()
            firstFrame(connect(used))
            assertTicketInvalid(connect(used))
            awaitReleased()
        }

        @Test
        fun `O14 티켓은 맞지만 그룹 Hash 가 없으면 409 GAME_NOT_OPEN 이고 구독이 남지 않는다`() {
            group()
            val issued = ticket()
            redis.del(groupKey)
            connect(issued).expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("GAME_NOT_OPEN")
            // 오류 응답 전에 detach 가 SUNSUBSCRIBE 확인까지 마친다
            assertThat(shardSubscribers()).isZero()
            assertThat(registry.connectionCount(gid)).isZero()
        }

        @Test
        fun `O23 snapshot 의 entries 원문은 조회와 같은 기준 문자열과 바이트까지 같다`() {
            group()
            val first = firstFrame(connect(ticket(MINJI)))
            // 같은 문자열을 app:api BoardApiTest O23 이 현황판 조회 응답에 대해 확인한다
            assertThat(BoardFixtures.rawEntries(first.data()!!)).isEqualTo(BoardFixtures.MINJI_ENTRIES_JSON)
            assertThat(first.id()).isEqualTo("${BoardFixtures.VERSION}")
            awaitReleased()
        }

        @Test
        fun `O27 연결 때 이미 랭킹이 확정됐으면 snapshot 하나 뒤 스트림이 끝난다`() {
            group(BoardFixtures.decidedHash(result = "FAILED_IMMEDIATE") + ("rankingFinal" to "1"))
            val frames = stream(connect(ticket())).responseBody.collectList().block(TIMEOUT)!!
            assertThat(frames).hasSize(1)
            assertThat(frames.single().event()).isEqualTo("board.snapshot")
            assertThat(frames.single().id()).isEqualTo("${BoardFixtures.VERSION + 1}")
            assertThat(data(frames.single())["groupStatus"]).isEqualTo("FAILED")
            awaitReleased()
        }

        @Test
        fun `O27 결과는 있지만 랭킹이 잠정이면 snapshot 뒤에도 스트림이 열려 있다`() {
            group(BoardFixtures.decidedHash()) // rankingFinal = 0
            val frames = stream(connect(ticket())).responseBody.take(Duration.ofMillis(700)).collectList().block(TIMEOUT)!!
            assertThat(frames.map { it.event() }).containsExactly("board.snapshot")
            assertThat(data(frames.single())["groupStatus"]).isEqualTo("FAILED")
            awaitReleased()
        }

        @Test
        fun `O28 Last-Event-ID 를 보내도 첫 프레임은 지금 version 의 snapshot 이다`() {
            group(BoardFixtures.inProgressHash() + ("version" to "10"))
            val frames = stream(connect(ticket(), lastEventId = "3")).responseBody.take(Duration.ofMillis(700)).collectList().block(TIMEOUT)!!
            assertThat(frames).isNotEmpty
            assertThat(frames.first().event()).isEqualTo("board.snapshot")
            assertThat(frames.first().id()).isEqualTo("10")
            assertThat(frames.map { it.event() }).containsOnly("board.snapshot")
            awaitReleased()
        }

        @Test
        fun `요청이 끝나면 스트림 본문을 수집하지 않았어도 연결을 허브에 돌려준다`(): Unit = runBlocking {
            val connection = hub.attach(gid)
            assertThat(registry.connectionCount(gid)).isEqualTo(1)
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(EVENTS))
            exchange.attributes[SseConnectionReleaseFilter.ATTRIBUTE] = connection
            // 컨트롤러가 attach 뒤 돌아왔지만 본문(Flow)을 아무도 수집하지 않고 요청이 끝난 경우
            releaseFilter.filter(exchange) { Mono.empty() }.block(TIMEOUT)
            awaitReleased()
        }

        @Test
        fun `O29 모든 연결에서 구독 확인이 board_read 보다 먼저다`() {
            group()
            calls.clear()
            // 첫 연결은 구독을 새로 열고, 둘째는 열린 구독을 나눠 받는다 — 둘 다 board_read 전에 구독이 서 있어야 한다
            val firstResult = stream(connect(ticket()))
            val first = firstResult.responseBody
            val second = stream(connect(ticket(BoardFixtures.JUNHO))).responseBody
            assertThat(first.take(1).blockFirst(TIMEOUT)!!.event()).isEqualTo("board.snapshot")
            assertThat(second.take(1).blockFirst(TIMEOUT)!!.event()).isEqualTo("board.snapshot")
            awaitReleased()

            // 연결 둘이 다 끊긴 뒤 다시 붙으면 구독을 새로 연다 — 그때도 구독이 먼저다
            firstFrame(connect(ticket()))
            awaitReleased()

            val mine = calls.filter { it.contains(":${gid.value}") }
            val subscribed = "subscribed:${gid.value}"
            fun read(attached: Int) = "read:${gid.value}:STREAM:attached=$attached"
            // 둘째 연결은 구독 확인이 따로 없으므로, 읽을 때 자기까지 붙어 있는지(연결 수 2)로 attach 가 먼저임을 본다
            assertThat(mine).containsExactly(subscribed, read(1), read(2), subscribed, read(1))
        }
    }
