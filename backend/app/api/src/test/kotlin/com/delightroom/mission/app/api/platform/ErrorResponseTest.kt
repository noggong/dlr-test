package com.delightroom.mission.app.api.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.app.api.web.FallbackExceptionHandler
import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcTransientResourceException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.ConnectException

/**
 * ORACLE platform E1 ~ E3 — 처리하지 못한 예외의 오류 응답(prd D21 · LLD platform §4.1).
 * 컨테이너를 멈추지 않고 시험용 라우트(`/__fault/{kind}` — 컨트롤러가 처리하지 못한 예외와 같은 길)와 시험용 WebFilter(`X-Fault` 헤더)가
 * 실제 Spring · 드라이버 예외 타입을 던진다. 두 시험용 빈은 이 클래스의 컨텍스트에만 있다(라우트 스모크 테스트에 섞이지 않는다).
 */
@SpringBootTest
@Import(MutableClockConfiguration::class, ErrorResponseTest.FaultConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
class ErrorResponseTest
    @Autowired
    constructor(context: ApplicationContext) {
        private val client = WebTestClient.bindToApplicationContext(context).build()
        private val json = jacksonObjectMapper()
        private val logger = LoggerFactory.getLogger(FallbackExceptionHandler::class.java) as Logger
        private val logs = ListAppender<ILoggingEvent>()

        @TestConfiguration
        class FaultConfiguration {
            /** 컨트롤러가 처리하지 못한 예외 — `@RestControllerAdvice` 가 잡지 않아 WebExceptionHandler 로 간다. */
            @Bean
            fun faultRoutes(): RouterFunction<ServerResponse> = router {
                GET("/__fault/{kind}") { request -> Mono.error(fault(request.pathVariable("kind"))) }
            }

            /** 컨트롤러 밖 — 인증 필터처럼 WebFilter 가 그 자리에서(동기) 던진다. */
            @Bean
            fun faultFilter(): WebFilter = WebFilter { exchange, chain ->
                when (val kind = exchange.request.headers.getFirst(FAULT_HEADER)) {
                    null -> chain.filter(exchange)
                    "unauthorized" -> throw BusinessException(ErrorCode.UNAUTHORIZED, "필터가 거부했습니다.")
                    else -> throw fault(kind)
                }
            }
        }

        @BeforeEach
        fun attachLogs() {
            logs.start()
            logger.addAppender(logs)
        }

        @AfterEach
        fun detachLogs() {
            logger.detachAppender(logs)
            logs.stop()
        }

        /** 「고정 본문」 — JSON · 키 둘 · message 가 그 코드의 defaultMessage 와 같다. 본문 원문을 돌려준다. */
        private fun assertFixedBody(spec: WebTestClient.ResponseSpec, code: ErrorCode): String {
            val raw = spec.expectStatus().isEqualTo(code.httpStatus)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String::class.java).returnResult().responseBody!!
            val body: Map<String, Any?> = json.readValue(raw)
            assertThat(body).containsExactlyInAnyOrderEntriesOf(mapOf("code" to code.name, "message" to code.defaultMessage))
            return raw
        }

        private fun assertNoInternals(raw: String) {
            listOf(MARKER, "SELECT", "db-host", "at com.", "Exception").forEach { assertThat(raw).doesNotContain(it) }
        }

        private fun get(path: String, fault: String? = null) =
            client.get().uri(path).apply { if (fault != null) header(FAULT_HEADER, fault) }.exchange()

        private fun logLines(): List<String> = logs.list.filter { it.level == Level.ERROR }.map {
            "${it.formattedMessage} ${it.throwableProxy?.className} ${it.throwableProxy?.message}"
        }

        @TestFactory
        fun `E1 의존 서비스 장애는 503 UNAVAILABLE 고정 본문이다`(): List<DynamicTest> =
            UNAVAILABLE_KINDS.map { kind ->
                DynamicTest.dynamicTest(kind) {
                    logs.list.clear()
                    assertNoInternals(assertFixedBody(get("/__fault/$kind"), ErrorCode.UNAVAILABLE))
                    assertThat(logLines()).singleElement().asString().startsWith("http.unavailable GET /__fault/$kind")
                }
            }

        @TestFactory
        fun `E2 그 밖의 예외는 500 INTERNAL_ERROR 이고 본문에 내부 사정이 없다`(): List<DynamicTest> =
            INTERNAL_KINDS.map { kind ->
                DynamicTest.dynamicTest(kind) {
                    logs.list.clear()
                    assertNoInternals(assertFixedBody(get("/__fault/$kind"), ErrorCode.INTERNAL_ERROR))
                    // 원인은 서버 로그에 남는다
                    assertThat(logLines()).singleElement().asString()
                        .startsWith("http.internal_error GET /__fault/$kind")
                        .contains(EXPECTED_TYPE.getValue(kind))
                }
            }

        @Test
        fun `E2 서버 로그에는 표지가 남는다`() {
            assertFixedBody(get("/__fault/illegal"), ErrorCode.INTERNAL_ERROR)
            assertThat(logLines()).singleElement().asString().contains("java.lang.IllegalStateException").contains(MARKER)
        }

        @Test
        fun `E3 WebFilter 에서 난 예외도 같은 형식이다`() {
            assertNoInternals(assertFixedBody(get("/health", fault = "redis-connection"), ErrorCode.UNAVAILABLE))
            assertNoInternals(assertFixedBody(get("/health", fault = "illegal"), ErrorCode.INTERNAL_ERROR))
            assertThat(logLines()).hasSize(2)

            logs.list.clear()
            get("/health", fault = "unauthorized")
                .expectStatus().isEqualTo(401)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("필터가 거부했습니다.")
            assertThat(logLines()).isEmpty() // 비즈니스 거부는 서버 오류 로그가 아니다
        }

        @Test
        fun `E3 Spring 요청 오류와 인증 필터의 401 은 그대로다`() {
            get("/no-such-path").expectStatus().isNotFound
            get("/api/v1/games/today")
                .expectStatus().isUnauthorized
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
            assertThat(logLines()).isEmpty()
        }

        private companion object {
            const val FAULT_HEADER = "X-Fault"
            const val MARKER = "MARKER-7f3c"

            val UNAVAILABLE_KINDS = listOf(
                "redis-connection", "redis-timeout", "r2dbc-acquire", "redis-system-connection",
                "redis-system-timeout", "r2dbc-transient", "channel-lost", "connect",
            )
            val INTERNAL_KINDS = listOf("illegal", "redis-script", "bad-grammar", "npe")
            val EXPECTED_TYPE = mapOf(
                "illegal" to "java.lang.IllegalStateException",
                "redis-script" to "org.springframework.data.redis.RedisSystemException",
                "bad-grammar" to "io.r2dbc.spi.R2dbcBadGrammarException",
                "npe" to "java.lang.NullPointerException",
            )

            /** 메시지에는 일부러 내부 사정(호스트 · SQL · 표지)을 넣는다 — 응답 본문에 새지 않아야 한다. */
            fun fault(kind: String): Throwable = when (kind) {
                "redis-connection" -> RedisConnectionFailureException("Unable to connect to Redis db-host:6379 $MARKER")
                "redis-timeout" -> QueryTimeoutException("Redis command timed out $MARKER")
                "r2dbc-acquire" -> DataAccessResourceFailureException(
                    "Failed to obtain R2DBC Connection",
                    R2dbcNonTransientResourceException("Connection refused: db-host/10.0.0.5:5432 $MARKER"),
                )
                "redis-system-connection" -> RedisSystemException("Redis exception", RedisConnectionException("Unable to connect to db-host:6379 $MARKER"))
                "redis-system-timeout" -> RedisSystemException("Redis exception", RedisCommandTimeoutException("Command timed out after 5 second(s) $MARKER"))
                "r2dbc-transient" -> R2dbcTransientResourceException("sorry, too many clients already $MARKER")
                "channel-lost" -> GroupChannelLostException("그룹 채널 구독 실패: 42 $MARKER")
                "connect" -> IllegalStateException("wrapped $MARKER", ConnectException("Connection refused: db-host/10.0.0.5:6379"))
                "illegal" -> IllegalStateException("$MARKER SELECT password FROM users at db-host:5432")
                "redis-script" -> RedisSystemException(
                    "Error in execution",
                    RedisCommandExecutionException("ERR user_script:12: $MARKER Script attempted to access nonexistent global variable"),
                )
                "bad-grammar" -> R2dbcBadGrammarException("syntax error at or near \"SELEC\" $MARKER", "42601", 0, "SELEC * FROM users")
                "npe" -> NullPointerException(MARKER)
                else -> error("모르는 fault: $kind")
            }
        }
    }
