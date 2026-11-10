package com.delightroom.mission.app.api

import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.core.testfixture.OpenApiContract
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import java.time.Clock

/** openapi.yaml 의 api 오퍼레이션이 모두 라우트로 있고, 지금은 501 NOT_IMPLEMENTED 를 돌려준다. */
@SpringBootTest
@Import(MutableClockConfiguration::class)
class RouteSmokeTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val clock: Clock,
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

        @TestFactory
        fun `모든 오퍼레이션은 501 을 돌려준다`(): List<DynamicTest> =
            operations.map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") {
                    val request = client.method(HttpMethod.valueOf(op.method)).uri(op.path)
                    val exchange = op.requestExample
                        ?.let { request.contentType(MediaType.APPLICATION_JSON).bodyValue(it) }
                        ?: request
                    exchange.exchange()
                        .expectStatus().isEqualTo(501)
                        .expectBody().jsonPath("$.code").isEqualTo("NOT_IMPLEMENTED")
                }
            }
    }

internal fun registeredRoutes(context: ApplicationContext): List<String> =
    context.getBeansOfType(RequestMappingHandlerMapping::class.java).values
        .flatMap { it.handlerMethods.entries }
        .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.delightroom.mission") }
        .flatMap { (info, _) ->
            info.methodsCondition.methods.flatMap { method ->
                info.patternsCondition.patterns.map { "${method.name} ${it.patternString}" }
            }
        }
