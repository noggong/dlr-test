package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.probe.CurrentUserProbeController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.OffsetDateTime

/**
 * ORACLE auth-signup O28 · O29 — 인증 필터(LLD §3.6 · SPEC AS14). 보호 경로는 `GET /api/v1/games/today`
 * (지금은 501 — 기능이 붙어도 「401 이 아님」으로 본다). `@CurrentUser` 주입은 테스트 전용 컨트롤러로 확인한다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class, CurrentUserProbeController::class)
class AccessTokenFilterTest
    @Autowired
    constructor(
        context: ApplicationContext,
        private val clock: MutableClock,
        private val tokenIssuer: TokenIssuer,
        private val gameProperties: GameProperties,
    ) {
        private val client = WebTestClient.bindToApplicationContext(context).build()
        private val account = Account(UserId(42), LoginId("alice_01"), "\$2a\$10\$x", GroupId(7), GroupName("새벽반"))

        @BeforeEach
        fun resetClock() = clock.set(OffsetDateTime.parse(MutableClockConfiguration.DEFAULT_NOW).toInstant())

        private fun issue() = tokenIssuer.issue(account, "3f2b6c1e-8a4d-4f0e-9b7a-1c2d3e4f5a6b", gameProperties.now(clock))

        private fun get(path: String = PROTECTED, access: String? = null, bearer: String? = null) =
            client.get().uri(path)
                .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
                .apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") }
                .exchange()

        private fun expectUnauthorized(spec: WebTestClient.ResponseSpec) {
            spec.expectStatus().isEqualTo(401)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("인증이 필요합니다.")
        }

        private fun statusOf(spec: WebTestClient.ResponseSpec): Int =
            spec.returnResult(String::class.java).status.value()

        @Test
        fun `O28 쿠키 없음은 401 UNAUTHORIZED`() = expectUnauthorized(get())

        @Test
        fun `O28 위조 토큰은 401 UNAUTHORIZED`() {
            val otherKey = JwtTokenIssuer(AuthProperties(jwtSecret = "another-secret-that-is-32-bytes-long!!"))
            val forged = otherKey.issue(account, "jti", gameProperties.now(clock)).access
            expectUnauthorized(get(access = forged))
            expectUnauthorized(get(access = "not-a-jwt"))
        }

        @Test
        fun `O28 만료(시계 +3600초)는 401 UNAUTHORIZED`() {
            val access = issue().access
            clock.advance(Duration.ofSeconds(3600))
            expectUnauthorized(get(access = access))
        }

        @Test
        fun `O28 refresh 토큰을 access 쿠키에 넣으면 401 UNAUTHORIZED`() = expectUnauthorized(get(access = issue().refresh))

        @Test
        fun `O28 Authorization Bearer 만 보내면 401 UNAUTHORIZED`() = expectUnauthorized(get(bearer = issue().access))

        @Test
        fun `O28 유효 access 쿠키는 401 이 아니고 +3599초까지 401 이 아니다`() {
            val access = issue().access
            assertThat(statusOf(get(access = access))).isNotEqualTo(401)
            clock.advance(Duration.ofSeconds(3599))
            assertThat(statusOf(get(access = access))).isNotEqualTo(401)
        }

        @Test
        fun `O29 sess 키를 지워도 유효 access 쿠키는 401 이 아니다`() {
            val access = issue().access
            val sessKey = "sess:{${account.userId.value}}"
            redisCli("SET", sessKey, "3f2b6c1e-8a4d-4f0e-9b7a-1c2d3e4f5a6b")
            assertThat(statusOf(get(access = access))).isNotEqualTo(401)

            redisCli("DEL", sessKey)
            assertThat(redisCli("EXISTS", sessKey)).isEqualTo("0")
            assertThat(statusOf(get(access = access))).isNotEqualTo(401)
        }

        @Test
        fun `CurrentUser 는 토큰 클레임을 AuthPrincipal 로 받는다`() {
            get(CurrentUserProbeController.PATH, access = issue().access)
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.userId").isEqualTo(42)
                .jsonPath("$.loginId").isEqualTo("alice_01")
                .jsonPath("$.groupId").isEqualTo(7)
        }

        @Test
        fun `공개 경로와 api 밖 경로는 필터가 막지 않는다`() {
            listOf("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh").forEach { path ->
                val status = client.post().uri(path).exchange().returnResult(String::class.java).status.value()
                assertThat(status).describedAs(path).isNotEqualTo(401)
            }
            assertThat(statusOf(get("/health"))).isNotEqualTo(401)
            // 공개 경로라도 POST 가 아니면 보호 대상이다 — 로그아웃은 보호 경로(AS14)
            expectUnauthorized(client.post().uri("/api/v1/auth/logout").exchange())
        }

        @Test
        fun `requiresAuth 는 api 접두와 공개 POST 3개만 가른다`() {
            assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.POST, "/api/v1/auth/login")).isFalse()
            assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.GET, "/api/v1/auth/login")).isTrue()
            assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.POST, "/api/v1/auth/logout")).isTrue()
            assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.GET, "/health")).isFalse()
            assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.GET, "/apix")).isFalse()
        }

        companion object {
            private const val PROTECTED = "/api/v1/games/today"

            /** O29 가 `sess:{<uid>}` 를 실제로 지우는 Redis(`redis:8-alpine` — compose 와 같은 판). JVM 이 끝나면 Ryuk 가 내린다. */
            private val redisContainer: GenericContainer<*> by lazy {
                GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).also { it.start() }
            }

            /** 앱 컨텍스트가 쓰는 같은 Redis 에 `redis-cli` 로 — api 테스트 클래스패스에 Redis 클라이언트를 더하지 않는다. */
            private fun redisCli(vararg args: String): String =
                redisContainer.execInContainer("redis-cli", *args).also { check(it.exitCode == 0) { it.stderr } }.stdout.trim()

            @JvmStatic
            @DynamicPropertySource
            fun redisProperties(registry: DynamicPropertyRegistry) {
                registry.add("spring.data.redis.host") { redisContainer.host }
                registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
            }
        }
    }
