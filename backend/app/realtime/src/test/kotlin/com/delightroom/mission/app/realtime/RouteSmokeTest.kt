package com.delightroom.mission.app.realtime

import com.delightroom.mission.app.realtime.dto.BoardSnapshotMessage
import com.delightroom.mission.app.realtime.dto.GroupPendingMessage
import com.delightroom.mission.app.realtime.dto.GroupRankingFinalMessage
import com.delightroom.mission.app.realtime.dto.GroupResultMessage
import com.delightroom.mission.app.realtime.dto.MemberEntry
import com.delightroom.mission.app.realtime.dto.MemberProgressedMessage
import com.delightroom.mission.app.realtime.dto.RealtimeEvent
import com.delightroom.mission.core.testfixture.AsyncApiContract
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
import java.lang.reflect.Modifier

/**
 * openapi.yaml 의 realtime 오퍼레이션(SSE 채널)이 라우트로 있고, 아직 구현하지 않은 것은 연결 전에 501 JSON 을 돌려준다.
 * 구현한 것([IMPLEMENTED])은 티켓 없이 부르면 Redis 를 부르지 않고 401 REALTIME_TICKET_INVALID JSON 이다.
 * asyncapi.yaml 의 메시지 이름 · payload 필드는 [RealtimeEvent] · `RealtimeMessages` 와 같다(ORACLE realtime-board O30).
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
class RouteSmokeTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
    ) {
        private val client = WebTestClient.bindToApplicationContext(context).build()
        private val operations = OpenApiContract.operations("realtime")

        @Test
        fun `SSE 라우트와 openapi 오퍼레이션이 일치한다`() {
            val routes = context.getBeansOfType(RequestMappingHandlerMapping::class.java).values
                .flatMap { it.handlerMethods.entries }
                .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.delightroom.mission") }
                .flatMap { (info, _) ->
                    info.methodsCondition.methods.flatMap { m -> info.patternsCondition.patterns.map { "${m.name} ${it.patternString}" } }
                }
            assertThat(routes).containsExactlyInAnyOrderElementsOf(operations.map { "${it.method} ${it.path}" })
        }

        @TestFactory
        fun `구현하지 않은 오퍼레이션은 501 을 돌려준다`(): List<DynamicTest> =
            operations.filterNot { "${it.method} ${it.path}" in IMPLEMENTED }.map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") {
                    client.method(HttpMethod.valueOf(op.method))
                        .uri { b -> b.path(op.path).apply { op.queryExamples.forEach { (k, v) -> queryParam(k, v) } }.build() }
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus().isEqualTo(501)
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                        .expectBody().jsonPath("$.code").isEqualTo("NOT_IMPLEMENTED")
                }
            }

        @TestFactory
        fun `구현한 오퍼레이션은 티켓 없이 부르면 401 REALTIME_TICKET_INVALID 를 돌려준다`(): List<DynamicTest> =
            operations.filter { "${it.method} ${it.path}" in IMPLEMENTED }.map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") {
                    client.method(HttpMethod.valueOf(op.method)).uri(op.path)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus().isEqualTo(401)
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                        .expectBody().jsonPath("$.code").isEqualTo("REALTIME_TICKET_INVALID")
                }
            }

        @Test
        fun `asyncapi 메시지 이름은 RealtimeEvent 5개와 같다`() {
            assertThat(AsyncApiContract.messageNames()).containsExactlyInAnyOrderElementsOf(MESSAGES.keys)
        }

        @TestFactory
        fun `asyncapi 메시지 payload 필드는 data class 필드와 같다`(): List<DynamicTest> =
            MESSAGES.map { (name, type) ->
                DynamicTest.dynamicTest(name) {
                    assertThat(AsyncApiContract.payloadFields(name)).containsExactlyInAnyOrderElementsOf(fieldsOf(type))
                }
            }

        @Test
        fun `MemberEntry 는 7필드이고 계약 파일 어디에도 cheatFailed 가 없다`() {
            assertThat(fieldsOf(MemberEntry::class.java)).hasSize(7)
            assertThat(AsyncApiContract.schemaFields("MemberEntry")).containsExactlyInAnyOrderElementsOf(fieldsOf(MemberEntry::class.java))
            assertThat(OpenApiContract.schemaFields("MemberEntry")).containsExactlyInAnyOrderElementsOf(fieldsOf(MemberEntry::class.java))
            assertThat(AsyncApiContract.text()).doesNotContain("cheatFailed")
            assertThat(OpenApiContract.text()).doesNotContain("cheatFailed")
        }
    }

/** SSE `event:` 이름 → `data:` 의 data class(SPEC realtime-board §6). */
private val MESSAGES: Map<String, Class<*>> = mapOf(
    RealtimeEvent.BOARD_SNAPSHOT to BoardSnapshotMessage::class.java,
    RealtimeEvent.MEMBER_PROGRESSED to MemberProgressedMessage::class.java,
    RealtimeEvent.GROUP_PENDING to GroupPendingMessage::class.java,
    RealtimeEvent.GROUP_RESULT to GroupResultMessage::class.java,
    RealtimeEvent.GROUP_RANKING_FINAL to GroupRankingFinalMessage::class.java,
)

/** data class 의 직렬화 필드 — 인스턴스 필드 이름(Jackson 이 그대로 키로 쓴다). */
private fun fieldsOf(type: Class<*>): List<String> =
    type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }

/** 구현을 마친 오퍼레이션. */
private val IMPLEMENTED = setOf(
    "GET /realtime/v1/games/today/events",
)
