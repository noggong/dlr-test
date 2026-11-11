package com.delightroom.mission.app.api.auth

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.app.api.RedisTestContainer
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.infra.redis.RedisKeys
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.junit.jupiter.api.AfterEach
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import java.time.OffsetDateTime
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * auth API 통합 테스트의 바탕 — 앱 컨텍스트 + Testcontainers PostgreSQL(RouteSmokeTest 와 같은 컨텍스트를 나눠 쓴다).
 * Redis 컨테이너도 함께 띄운다 — 로그인 · refresh · 로그아웃 테스트가 `sess:{<uid>}` 를 직접 읽는다.
 * 테스트끼리 DB 를 비우지 않으므로 아이디 · 그룹명은 [uniqueLoginId] · [uniqueGroupName] 으로 매번 새로 만든다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class, RedisTestContainer.Initializer::class])
abstract class AuthApiTestSupport {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    protected lateinit var db: DatabaseClient

    @Autowired
    protected lateinit var clock: MutableClock

    @Autowired
    protected lateinit var redis: ReactiveStringRedisTemplate

    @Autowired
    protected lateinit var redisKeys: RedisKeys

    protected val client: WebTestClient by lazy { WebTestClient.bindToApplicationContext(context).build() }

    /** 컨텍스트를 나눠 쓰므로 옮긴 시계를 테스트마다 되돌린다. */
    @AfterEach
    fun resetClock() {
        clock.set(OffsetDateTime.parse(MutableClockConfiguration.DEFAULT_NOW).toInstant())
    }

    protected fun setClock(at: String) = clock.set(OffsetDateTime.parse(at).toInstant())

    // ---- 로그인 · refresh · 로그아웃 ----

    protected fun postLogin(loginId: String, password: String = PASSWORD): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(LOGIN).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("loginId" to loginId, "password" to password))
            .exchange().expectBody(MAP_TYPE).returnResult()

    /** 쿠키 없이도 부를 수 있게 [refresh] 가 `null` 이면 싣지 않는다. */
    protected fun postRefresh(refresh: String?): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(REFRESH)
            .apply { if (refresh != null) cookie(AuthCookies.REFRESH, refresh) }
            .exchange().expectBody(MAP_TYPE).returnResult()

    protected fun postLogout(access: String?, refresh: String?): EntityExchangeResult<ByteArray> =
        client.post().uri(LOGOUT)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .apply { if (refresh != null) cookie(AuthCookies.REFRESH, refresh) }
            .exchange().expectBody().returnResult()

    /** 새 그룹으로 가입한 새 계정의 아이디. */
    protected fun newAccount(): String =
        uniqueLoginId().also { id ->
            val result = signUp(id, uniqueGroupName())
            check(result.status.value() == 201) { "준비 가입 실패: ${result.responseBody}" }
        }

    /** 가입 → 로그인 — (아이디, 로그인 응답). */
    protected fun loggedIn(): Pair<String, EntityExchangeResult<Map<String, Any>>> {
        val id = newAccount()
        val result = postLogin(id)
        check(result.status.value() == 200) { "준비 로그인 실패: ${result.responseBody}" }
        return id to result
    }

    protected fun userIdOf(loginId: String): UserId =
        UserId(queryLong("SELECT id FROM users WHERE login_id = :id", "id" to loginId)!!)

    protected fun sessionValue(loginId: String): String? =
        runBlocking { redis.opsForValue().get(redisKeys.session(userIdOf(loginId))).awaitSingleOrNull() }

    protected fun sessionTtlSeconds(loginId: String): Long =
        runBlocking { redis.getExpire(redisKeys.session(userIdOf(loginId))).awaitSingle().seconds }

    protected fun sessionKeyCount(): Long =
        runBlocking { redis.keys("sess:*").count().awaitSingle() }

    protected fun signUp(
        loginId: String,
        groupName: String,
        mode: String = "NEW",
        password: String = PASSWORD,
    ): EntityExchangeResult<Map<String, Any>> =
        postSignUp(mapOf("loginId" to loginId, "password" to password, "group" to mapOf("mode" to mode, "name" to groupName)))

    protected fun postSignUp(body: Any): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(SIGNUP).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange()
            .expectBody(MAP_TYPE)
            .returnResult()

    /** 정원까지 채운 그룹 — 첫 사람이 NEW, 나머지가 EXISTING. 만든 아이디 목록을 돌려준다. */
    protected fun groupOf(groupName: String, size: Int): List<String> =
        List(size) { i ->
            uniqueLoginId().also { id ->
                val result = signUp(id, groupName, if (i == 0) "NEW" else "EXISTING")
                check(result.status.value() == 201) { "준비 가입 실패: ${result.responseBody}" }
            }
        }

    protected fun memberCount(groupName: String): Long? =
        queryLong("SELECT member_count FROM groups WHERE name_key = lower(:name)", "name" to groupName)

    protected fun userCountOf(groupName: String): Long =
        queryLong(
            "SELECT count(*) FROM users u JOIN groups g ON g.id = u.group_id WHERE g.name_key = lower(:name)",
            "name" to groupName,
        )!!

    protected fun count(table: String): Long = queryLong("SELECT count(*) FROM $table")!!

    protected fun queryLong(sql: String, vararg binds: Pair<String, Any>): Long? =
        runBlocking {
            binds.fold(db.sql(sql)) { spec, (k, v) -> spec.bind(k, v) }
                .map { row -> (row.get(0) as Number).toLong() }
                .all().collectList().awaitSingle().firstOrNull()
        }

    /** ORACLE I5 — `member_count` 가 소속 유저 수와 다르거나 10 을 넘는 그룹 수. */
    protected fun brokenGroupCount(): Long =
        queryLong(
            "SELECT count(*) FROM groups g WHERE member_count <> " +
                "(SELECT count(*) FROM users u WHERE u.group_id = g.id) OR member_count > 10",
        )!!

    companion object {
        const val SIGNUP = "/api/v1/auth/signup"
        const val LOGIN = "/api/v1/auth/login"
        const val REFRESH = "/api/v1/auth/refresh"
        const val LOGOUT = "/api/v1/auth/logout"
        const val PASSWORD = "correct-horse-9"

        private val seq = AtomicInteger()
        private val run = Math.floorMod(System.nanoTime(), 46_656L).toString(36).padStart(3, '0')

        val MAP_TYPE = object : org.springframework.core.ParameterizedTypeReference<Map<String, Any>>() {}

        /** `Set-Cookie` 헤더 중 [name] 쿠키의 전체 줄. */
        fun setCookie(result: EntityExchangeResult<*>, name: String): String? =
            result.responseHeaders[HttpHeaders.SET_COOKIE].orEmpty().firstOrNull { it.startsWith("$name=") }

        /** `Set-Cookie` 헤더 중 [name] 쿠키의 값. */
        fun cookieValue(result: EntityExchangeResult<*>, name: String): String =
            setCookie(result, name)!!.substringBefore(';').substringAfter('=')

        fun jtiOf(refreshToken: String): String = SignedJWT.parse(refreshToken).jwtClaimsSet.jwtid

        /** `^[a-z0-9_]{4,20}$` 안의 새 아이디. */
        fun uniqueLoginId(): String = "u${run}_${seq.incrementAndGet()}"

        /** 2~20자 그룹명 — [base] 는 12자 이하로. */
        fun uniqueGroupName(base: String = "Team"): String = "$base-$run${seq.incrementAndGet()}"
    }
}
