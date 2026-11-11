package com.delightroom.mission.app.realtime

import com.delightroom.mission.app.realtime.dto.BoardSnapshotMessage
import com.delightroom.mission.app.realtime.dto.GroupPendingMessage
import com.delightroom.mission.app.realtime.dto.GroupRankingFinalMessage
import com.delightroom.mission.app.realtime.dto.GroupResultMessage
import com.delightroom.mission.app.realtime.dto.MemberEntry
import com.delightroom.mission.app.realtime.dto.MemberProgressedMessage
import com.delightroom.mission.app.realtime.dto.RealtimeEvent
import com.delightroom.mission.app.realtime.dto.RealtimeMessage
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
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * openapi.yaml 의 realtime 오퍼레이션(SSE 채널)이 라우트로 있고, 아직 구현하지 않은 것은 연결 전에 501 JSON 을 돌려준다.
 * 구현한 것([IMPLEMENTED])은 티켓 없이 부르면 Redis 를 부르지 않고 401 REALTIME_TICKET_INVALID JSON 이다.
 * asyncapi.yaml 의 메시지 이름 · payload 필드는 [RealtimeEvent] · `RealtimeMessages` 의 직렬화 결과와 같다(ORACLE realtime-board O30).
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
class RouteSmokeTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val json: JsonMapper,
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
        fun `asyncapi 메시지 이름은 RealtimeEvent 5개 · RealtimeMessage 하위 타입 5개와 같다`() {
            val constants = RealtimeEvent::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map { it.get(null) as String }
            assertThat(constants).containsExactlyInAnyOrderElementsOf(SAMPLES.keys)
            assertThat(RealtimeMessage::class.sealedSubclasses.map { it.java })
                .containsExactlyInAnyOrderElementsOf(SAMPLES.values.map { it.javaClass })
            assertThat(AsyncApiContract.messageNames()).containsExactlyInAnyOrderElementsOf(SAMPLES.keys)
            (AsyncApiContract.channelMessageNames() + AsyncApiContract.operationMessageNames()).forEach { (where, names) ->
                assertThat(names).describedAs(where).containsExactlyInAnyOrderElementsOf(SAMPLES.keys)
            }
        }

        @TestFactory
        fun `asyncapi 메시지 payload 필드는 앱 JsonMapper 직렬화 키와 같다`(): List<DynamicTest> =
            SAMPLES.map { (name, sample) ->
                DynamicTest.dynamicTest(name) {
                    assertThat(AsyncApiContract.payloadFields(name)).containsExactlyInAnyOrderElementsOf(keysOf(sample))
                }
            }

        @Test
        fun `MemberEntry 는 7필드이고 계약 파일 어디에도 cheatFailed 가 없다`() {
            val keys = keysOf(ENTRY)
            assertThat(keys).hasSize(7)
            assertThat(AsyncApiContract.schemaFields("MemberEntry")).containsExactlyInAnyOrderElementsOf(keys)
            assertThat(OpenApiContract.schemaFields("MemberEntry")).containsExactlyInAnyOrderElementsOf(keys)
            assertThat(AsyncApiContract.text()).doesNotContain("cheatFailed")
            assertThat(OpenApiContract.text()).doesNotContain("cheatFailed")
        }

        /** 실제로 `data:` 에 실리는 키 — null 필드도 빠지지 않아야 한다(SPEC §2 · 필드 7개를 언제나 싣는다). */
        @Suppress("UNCHECKED_CAST")
        private fun keysOf(value: Any): Set<String> = (json.readValue(json.writeValueAsString(value), Map::class.java) as Map<String, Any?>).keys
    }

private val AT: OffsetDateTime = OffsetDateTime.parse("2026-11-12T07:30:00.910233+09:00")
private val DATE: LocalDate = LocalDate.parse("2026-11-12")

/** null 허용 필드를 null 로 둔 한 줄 — 직렬화가 null 을 빼면 키 대조에서 걸린다. */
private val ENTRY = MemberEntry("dohyun", 3, 0, "NOT_STARTED", false, null, null)

/** SSE `event:` 이름 → `data:` 의 견본(SPEC realtime-board §6). */
private val SAMPLES: Map<String, RealtimeMessage> = mapOf(
    RealtimeEvent.BOARD_SNAPSHOT to BoardSnapshotMessage(17, DATE, "IN_PROGRESS", listOf(ENTRY), AT),
    RealtimeEvent.MEMBER_PROGRESSED to MemberProgressedMessage(18, DATE, "IN_PROGRESS", "dohyun", listOf(ENTRY), AT),
    RealtimeEvent.GROUP_PENDING to GroupPendingMessage(31, DATE, "PENDING", AT),
    RealtimeEvent.GROUP_RESULT to GroupResultMessage(26, DATE, "FAILED", AT, true, listOf(ENTRY)),
    RealtimeEvent.GROUP_RANKING_FINAL to GroupRankingFinalMessage(34, DATE, "FAILED", AT, true, listOf(ENTRY)),
)

/** 구현을 마친 오퍼레이션. */
private val IMPLEMENTED = setOf(
    "GET /realtime/v1/games/today/events",
)
