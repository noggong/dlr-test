package com.delightroom.mission.app.api

import com.delightroom.mission.app.api.auth.AccessTokenWebFilter
import com.delightroom.mission.app.api.dto.BoardResponse
import com.delightroom.mission.app.api.dto.MeResponse
import com.delightroom.mission.app.api.dto.MemberEntryResponse
import com.delightroom.mission.app.api.dto.RealtimeTicketResponse
import com.delightroom.mission.common.error.ErrorCode
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
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * openapi.yaml 의 api 오퍼레이션이 모두 라우트로 있고(ORACLE platform C2), 계약 · `ErrorCode` 에 스켈레톤의 501 이 남지 않았다(C1).
 * 보호 경로(공개 POST 3개 밖 — SPEC AS14)는 쿠키가 없으면 401 이다. 오퍼레이션마다의 동작은 기능별 테스트(`app.api.auth.*` 등)가 본다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
class RouteSmokeTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val clock: Clock,
        private val json: JsonMapper,
    ) {
        private val client = WebTestClient.bindToApplicationContext(context).build()
        private val operations = OpenApiContract.operations("api")

        @Test
        fun `Clock 빈은 테스트에서 MutableClock 으로 바뀐다`() {
            assertThat(clock).isInstanceOf(MutableClock::class.java)
        }

        @Test
        fun `C2 컨트롤러 라우트와 openapi 오퍼레이션이 일치한다 — 데모 시계가 꺼지면 데모 전용은 없다`() {
            assertThat(operations.filter { it.demoOnly }).isNotEmpty() // 데모 전용을 읽지 못해 헛통과하지 않게
            assertThat(registeredRoutes(context)).containsExactlyInAnyOrderElementsOf(
                operations.filterNot { it.demoOnly }.map { "${it.method} ${it.path}" },
            )
        }

        private fun isProtected(op: OpenApiOperation) = AccessTokenWebFilter.requiresAuth(HttpMethod.valueOf(op.method), op.path)

        @Test
        fun `C1 계약과 ErrorCode 에 스켈레톤의 501 이 없다`() {
            val all = OpenApiContract.operations("api") + OpenApiContract.operations("realtime")
            assertThat(all).isNotEmpty().allSatisfy { assertThat(it.responseCodes).isNotEmpty() } // 응답 키를 읽지 못해 헛통과하지 않게
            assertThat(all.filter { "501" in it.responseCodes }.map { "${it.method} ${it.path}" }).isEmpty()
            assertThat(OpenApiContract.text()).doesNotContain("NOT_IMPLEMENTED").doesNotContain("NotImplemented")
            assertThat(ErrorCode.entries.filter { it.httpStatus == 501 }).isEmpty()
        }

        @TestFactory
        fun `보호 오퍼레이션은 쿠키가 없으면 401 을 돌려준다`(): List<DynamicTest> =
            operations.filterNot { it.demoOnly }.filter(::isProtected).map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") {
                    client.method(HttpMethod.valueOf(op.method)).uri(op.path).exchange()
                        .expectStatus().isEqualTo(401)
                        .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                }
            }

        @TestFactory
        fun `openapi 응답 스키마 필드는 앱 JsonMapper 로 직렬화한 DTO 키와 같다`(): List<DynamicTest> =
            SAMPLES.map { (schema, sample) ->
                DynamicTest.dynamicTest(schema) {
                    @Suppress("UNCHECKED_CAST")
                    val keys = (json.readValue(json.writeValueAsString(sample), Map::class.java) as Map<String, Any?>).keys
                    assertThat(OpenApiContract.schemaFields(schema)).containsExactlyInAnyOrderElementsOf(keys)
                }
            }
    }

/** null 허용 필드를 null 로 둔 한 줄 — 직렬화가 null 을 빼면 키 대조에서 걸린다. */
private val ENTRY = MemberEntryResponse("dohyun", 3, 0, "NOT_STARTED", false, null, null)

/** openapi 스키마 → 그 응답 DTO 견본(ORACLE realtime-board O30). */
private val SAMPLES: Map<String, Any> = mapOf(
    "MeResponse" to MeResponse("alice_01", "Team-A 아침반"),
    "MemberEntry" to ENTRY,
    "BoardResponse" to BoardResponse(LocalDate.parse("2026-11-12"), "IN_PROGRESS", 17, listOf(ENTRY)),
    "RealtimeTicketResponse" to RealtimeTicketResponse(
        "q3V9cX0bS2lQe1o7YfW8nB4tH6kR2mJ5pL0aD9sG1uE",
        OffsetDateTime.parse("2026-11-12T07:30:12.481000+09:00"),
    ),
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
