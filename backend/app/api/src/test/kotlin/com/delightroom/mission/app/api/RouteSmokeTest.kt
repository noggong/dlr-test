package com.delightroom.mission.app.api

import com.delightroom.mission.app.api.auth.AccessTokenWebFilter
import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.core.testfixture.OpenApiContract
import com.delightroom.mission.core.testfixture.OpenApiOperation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import java.time.Clock

/**
 * openapi.yaml 의 api 오퍼레이션이 모두 라우트로 있고, 아직 구현하지 않은 것은 501 NOT_IMPLEMENTED 를 돌려준다.
 * 보호 경로(공개 POST 3개 밖 — SPEC AS14)는 유효 access 쿠키를 실어 501, 쿠키가 없으면 401 이다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
class RouteSmokeTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val clock: Clock,
        private val tokenIssuer: TokenIssuer,
        private val gameProperties: GameProperties,
    ) {
        private val client = WebTestClient.bindToApplicationContext(context).build()
        private val operations = OpenApiContract.operations("api")

        @Test
        fun `Clock 빈은 테스트에서 MutableClock 으로 바뀐다`() {
            assertThat(clock).isInstanceOf(MutableClock::class.java)
        }

        @Test
        fun `컨트롤러 라우트와 openapi 오퍼레이션이 일치한다`() {
            assertThat(registeredRoutes(context)).containsExactlyInAnyOrderElementsOf(
                operations.map { "${it.method} ${it.path}" },
            )
        }

        private fun isProtected(op: OpenApiOperation) = AccessTokenWebFilter.requiresAuth(HttpMethod.valueOf(op.method), op.path)

        private fun access(): String {
            val account = Account(UserId(1), LoginId("smoke_01"), "", GroupId(1), GroupName("스모크"))
            return tokenIssuer.issue(account, "smoke", gameProperties.now(clock)).access
        }

        @TestFactory
        fun `구현 전 오퍼레이션은 501 을 돌려준다`(): List<DynamicTest> =
            operations.filterNot { "${it.method} ${it.path}" in IMPLEMENTED }.map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") {
                    val request = client.method(HttpMethod.valueOf(op.method)).uri(op.path)
                        .apply { if (isProtected(op)) cookie(AuthCookies.ACCESS, access()) }
                    val exchange = op.requestExample
                        ?.let { request.contentType(MediaType.APPLICATION_JSON).bodyValue(it) }
                        ?: request
                    exchange.exchange()
                        .expectStatus().isEqualTo(501)
                        .expectBody().jsonPath("$.code").isEqualTo("NOT_IMPLEMENTED")
                }
            }

        @TestFactory
        fun `보호 오퍼레이션은 쿠키가 없으면 401 을 돌려준다`(): List<DynamicTest> =
            operations.filter(::isProtected).map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") {
                    client.method(HttpMethod.valueOf(op.method)).uri(op.path).exchange()
                        .expectStatus().isEqualTo(401)
                        .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                }
            }
    }

/** 구현을 마쳐 501 목록에서 뺀 오퍼레이션 — 동작은 기능별 테스트(`app.api.auth.*` 등)가 본다. */
private val IMPLEMENTED = setOf(
    "POST /api/v1/auth/signup",
    "POST /api/v1/auth/login",
    "POST /api/v1/auth/refresh",
    "POST /api/v1/auth/logout",
    "GET /api/v1/games/today",
    "POST /api/v1/games/today/question-bank",
    "POST /api/v1/games/today/shown",
    "GET /api/v1/games/today/board",
    "POST /api/v1/realtime/tickets",
    "GET /api/v1/games/results/latest",
    "POST /api/v1/games/today/solves",
    "POST /api/v1/games/today/passes",
    "POST /api/v1/games/today/failures",
)

internal fun registeredRoutes(context: ApplicationContext): List<String> =
    context.getBeansOfType(RequestMappingHandlerMapping::class.java).values
        .flatMap { it.handlerMethods.entries }
        .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.delightroom.mission") }
        .flatMap { (info, _) ->
            info.methodsCondition.methods.flatMap { method ->
                info.patternsCondition.patterns.map { "${method.name} ${it.patternString}" }
            }
        }
