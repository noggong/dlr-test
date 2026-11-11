package com.delightroom.mission.app.realtime.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.delightroom.mission.app.realtime.realtimeboard.RealtimeTestRedis
import com.delightroom.mission.app.realtime.sse.SseConnectionRegistry
import com.delightroom.mission.app.realtime.web.FallbackExceptionHandler
import com.delightroom.mission.common.error.ErrorCode
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
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * ORACLE platform E4 — realtime 연결 전(응답을 시작하기 전) 실패가 JSON 오류다(prd D21 · LLD platform §4.1 SSE).
 * 운영 [GroupEventSubscriber] · [GroupBoardRepository] 빈을 감싸 [subscribeFault] · [readFault] 가 있을 때만 그 예외를 던진다 —
 * Redis 컨테이너는 멈추지 않는다(JVM 공용). 티켓은 저장소로 바로 넣는다(발급 API 는 app:api).
 */
@SpringBootTest
@Import(MutableClockConfiguration::class, StreamErrorResponseTest.FaultInjector::class)
class StreamErrorResponseTest
    @Autowired
    constructor(
        context: ApplicationContext,
        private val tickets: RealtimeTicketRepository,
        private val registry: SseConnectionRegistry,
    ) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun redis(registry: DynamicPropertyRegistry) = RealtimeTestRedis.register(registry)

            private const val EVENTS = "/realtime/v1/games/today/events"
            private const val MARKER = "MARKER-7f3c"

            val subscribeFault = AtomicReference<Throwable?>()
            val readFault = AtomicReference<Throwable?>()
        }

        /** 운영 빈을 감싼다 — 고장이 없으면 그대로 위임한다. */
        class FaultInjector : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                when (bean) {
                    is GroupEventSubscriber -> FaultySubscriber(bean)
                    is GroupBoardRepository -> FaultyBoards(bean)
                    else -> bean
                }
        }

        private class FaultySubscriber(private val delegate: GroupEventSubscriber) : GroupEventSubscriber, DisposableBean {
            override fun subscribe(groupId: GroupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage> {
                // 구독 확인(onSubscribed) 전에 끝난다 — Redis 에 붙지 못한 것과 같다
                val fault = subscribeFault.get() ?: return delegate.subscribe(groupId, onSubscribed)
                return flow { throw fault }
            }

            override fun destroy() {
                (delegate as? DisposableBean)?.destroy()
            }
        }

        private class FaultyBoards(private val delegate: GroupBoardRepository) : GroupBoardRepository {
            override suspend fun read(groupId: GroupId, date: LocalDate, userId: UserId, mode: BoardReadMode): BoardRead {
                readFault.get()?.let { throw it }
                return delegate.read(groupId, date, userId, mode)
            }
        }

        private val client = WebTestClient.bindToApplicationContext(context).build()
        private val json = jacksonObjectMapper()
        private val logger = LoggerFactory.getLogger(FallbackExceptionHandler::class.java) as Logger
        private val logs = ListAppender<ILoggingEvent>()
        private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))

        @BeforeEach
        fun attachLogs() {
            logs.start()
            logger.addAppender(logs)
        }

        @AfterEach
        fun reset() {
            subscribeFault.set(null)
            readFault.set(null)
            logger.detachAppender(logs)
            logs.stop()
        }

        /** 발급 API 와 같은 값 · TTL 로 티켓을 넣는다. */
        private fun ticket(): String = runBlocking {
            val value = RealtimeTicket.newValue(SecureRandom())
            val ticket = RealtimeTicket(value, UserId(BoardFixtures.MINJI), gid, BoardFixtures.DATE, Instant.now().plus(RealtimeTicket.TTL))
            check(tickets.saveNew(ticket, RealtimeTicket.TTL))
            value
        }

        private fun connect(ticket: String): WebTestClient.ResponseSpec =
            client.get().uri { it.path(EVENTS).queryParam("ticket", ticket).build() }
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()

        /** 고정 본문 · 내부 사정 없음 · 티켓 소모 · 연결 반환 · ERROR 로그 1줄(티켓 값 없음). */
        private fun assertRejected(issued: String, code: ErrorCode) {
            val raw = connect(issued).expectStatus().isEqualTo(code.httpStatus)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String::class.java).returnResult().responseBody!!
            val body: Map<String, Any?> = json.readValue(raw)
            assertThat(body).containsExactlyInAnyOrderEntriesOf(mapOf("code" to code.name, "message" to code.defaultMessage))
            listOf(MARKER, "db-host", "at com.", "Exception").forEach { assertThat(raw).doesNotContain(it) }

            val errors = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
            assertThat(errors).singleElement().asString()
                .startsWith("http.${code.name.lowercase()} GET $EVENTS")
                .doesNotContain(issued)

            // 티켓은 GETDEL 로 이미 썼다 — 클라이언트는 새 티켓을 받는다
            connect(issued).expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.code").isEqualTo("REALTIME_TICKET_INVALID")
            awaitReleased()
        }

        private fun awaitReleased() {
            val until = System.nanoTime() + 5_000_000_000L
            while (registry.connectionCount(gid) != 0) {
                check(System.nanoTime() < until) { "5초 안에 연결이 돌아오지 않음: ${registry.connectionCount(gid)}" }
                Thread.sleep(20)
            }
        }

        @Test
        fun `E4 티켓을 쓴 뒤 그룹 채널 구독이 실패하면 503 UNAVAILABLE JSON 이다`() {
            subscribeFault.set(RedisConnectionFailureException("Unable to connect to Redis db-host:6379 $MARKER"))
            assertRejected(ticket(), ErrorCode.UNAVAILABLE)
        }

        @Test
        fun `E4 현황 읽기에서 Redis 연결이 실패하면 503 UNAVAILABLE JSON 이다`() {
            readFault.set(RedisConnectionFailureException("Unable to connect to Redis db-host:6379 $MARKER"))
            assertRejected(ticket(), ErrorCode.UNAVAILABLE)
        }

        @Test
        fun `E4 현황 읽기의 서버 결함은 500 INTERNAL_ERROR JSON 이고 내부 사정이 없다`() {
            readFault.set(IllegalStateException("$MARKER group hash broken at db-host"))
            assertRejected(ticket(), ErrorCode.INTERNAL_ERROR)
        }
    }
