package com.delightroom.mission.app.api.platform

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.app.api.RedisTestContainer
import com.delightroom.mission.app.api.registeredRoutes
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.service.DemoClockService
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.core.testfixture.OpenApiContract
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.OffsetDateTime

/** ORACLE platform O45 · C2 — 데모 시계가 켜진 `api` 의 시각 이동 API(SPEC platform §6.3). 쿠키 없이 부른다(P14). */
@SpringBootTest(properties = ["game.demo.enabled=true"])
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class, RedisTestContainer.Initializer::class])
class DevClockApiTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val clock: MutableClock,
        private val redis: ReactiveStringRedisTemplate,
        private val demoClock: DemoClockService,
        private val game: GameProperties,
    ) {
        private val client = WebTestClient.bindToApplicationContext(context).build()

        private fun kst(s: String) = OffsetDateTime.parse("${s}+09:00").toInstant()

        /** 이동량 0 · 실제 = 게임 = [at] 에서 시작한다(컨텍스트를 나눠 쓴다). */
        private fun startAt(at: String) = runBlocking {
            redis.opsForValue().set(KEY, "0").awaitSingleOrNull()
            demoClock.syncOnce()
            clock.set(kst(at))
        }

        @BeforeEach
        fun setUp() {
            startAt("2026-11-11T07:30:00")
        }

        @AfterEach
        fun tearDown() {
            runBlocking { redis.delete(KEY).awaitSingleOrNull() }
            clock.set(OffsetDateTime.parse(MutableClockConfiguration.DEFAULT_NOW).toInstant())
        }

        private fun post(body: String) =
            client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

        @Test
        fun `O45 GET 은 쿠키 없이 지금 게임 시각과 그날 경계 · 옮길 수 있는 한계를 준다 — 필드가 openapi 와 같다`() {
            val body = client.get().uri(PATH).exchange().expectStatus().isOk
                .expectBody(Map::class.java).returnResult().responseBody!!
            assertThat(OffsetDateTime.parse(body["serverTime"] as String).toInstant()).isEqualTo(kst("2026-11-11T07:30:00"))
            assertThat(body["gameDate"]).isEqualTo("2026-11-11")
            assertThat(OffsetDateTime.parse(body["moveLimit"] as String).toInstant()).isEqualTo(kst("2026-11-11T07:59:55"))
            assertThat(OffsetDateTime.parse(body["rankingFinalAt"] as String).toInstant()).isEqualTo(kst("2026-11-11T08:00:03"))
            assertThat(OpenApiContract.schemaFields("DevClockResponse")).containsExactlyInAnyOrderElementsOf(body.keys.map { it as String })
        }

        @Test
        fun `O45 deadline 프리셋은 07시 59분 50초로 옮기고 Redis · 앱 시각이 함께 바뀐다`() {
            val body = post("""{"to":"deadline"}""").expectStatus().isOk
                .expectBody(Map::class.java).returnResult().responseBody!!
            assertThat(OffsetDateTime.parse(body["serverTime"] as String).toInstant()).isEqualTo(kst("2026-11-11T07:59:50"))
            assertThat(body["shiftSeconds"].toString().toDouble()).isEqualTo(1_790.0)
            assertThat(game.now(clock)).isEqualTo(kst("2026-11-11T07:59:50"))
            assertThat(runBlocking { redis.opsForValue().get(KEY).awaitSingleOrNull() }).isEqualTo("1790000000")
        }

        @Test
        fun `O45 절대 시각으로 옮기고, 08시 뒤에서는 start 로 다음 날 06시 59분 50초`() {
            post("""{"at":"2026-11-11T07:45:00+09:00"}""").expectStatus().isOk
            assertThat(game.now(clock)).isEqualTo(kst("2026-11-11T07:45:00"))

            startAt("2026-11-11T08:00:05")
            post("""{"to":"start"}""").expectStatus().isOk
                .expectBody().jsonPath("$.gameDate").isEqualTo("2026-11-12")
            assertThat(game.now(clock)).isEqualTo(kst("2026-11-12T06:59:50"))
        }

        @Test
        fun `O45 뒤로 · 경계를 넘는 이동은 409 CLOCK_MOVE_REJECTED 이고 시각은 그대로`() {
            listOf(
                """{"at":"2026-11-11T07:00:00+09:00"}""",
                """{"at":"2026-11-11T08:00:10+09:00"}""",
                """{"to":"start"}""",
            ).forEach { body ->
                post(body).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("CLOCK_MOVE_REJECTED")
            }
            assertThat(game.now(clock)).isEqualTo(kst("2026-11-11T07:30:00"))
        }

        @Test
        fun `O45 잘못된 본문은 400 INVALID_REQUEST`() {
            listOf(
                """{}""",
                """{"to":"deadline","at":"2026-11-11T07:45:00+09:00"}""",
                """{"to":"later"}""",
                """{"at":"2026-11-11T07:45:00"}""",
                """{"at":12}""",
            ).forEach { body ->
                post(body).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            }
        }

        @Test
        fun `C2 데모가 켜지면 라우트가 openapi 의 api 오퍼레이션 전부와 같다`() {
            assertThat(registeredRoutes(context)).containsExactlyInAnyOrderElementsOf(
                OpenApiContract.operations("api").map { "${it.method} ${it.path}" },
            )
        }

        private companion object {
            const val PATH = "/api/v1/dev/clock"
            const val KEY = "demo:clock"
        }
    }
