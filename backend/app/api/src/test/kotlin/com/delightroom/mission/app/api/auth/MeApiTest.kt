package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.time.Duration

/** 지금 로그인한 나 — ORACLE auth-signup O31 ~ O33 · O30. 계약은 SPEC §2.5 · AS13 · AS14 · AS18(prd D23). */
class MeApiTest : AuthApiTestSupport() {
    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var gameProperties: GameProperties

    private fun getMe(access: String? = null, bearer: String? = null): EntityExchangeResult<Map<String, Any>> =
        client.get().uri(ME)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") }
            .exchange().expectBody(MAP_TYPE).returnResult()

    private fun EntityExchangeResult<Map<String, Any>>.assertUnauthorized() {
        assertThat(status.value()).isEqualTo(401)
        assertThat(responseBody!!["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(responseHeaders.getCacheControl()).isEqualTo("no-store")
        assertThat(responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
    }

    /**
     * O31 — access 쿠키만 싣고 두 번 불러 둘 다 200 · 본문 = 로그인 응답의 `userId` · `groupName`(키 정확히 둘) ·
     * `no-store` · `Set-Cookie` 0개, 그리고 `sess:{<uid>}` 값 그대로 · TTL 은 줄기만 한다(AS18).
     */
    private fun assertMe(loginId: String, login: EntityExchangeResult<Map<String, Any>>, expectedGroupName: String) {
        val access = cookieValue(login, AuthCookies.ACCESS)
        val jti = sessionValue(loginId)
        val ttl = sessionTtlSeconds(loginId)

        repeat(2) {
            val me = getMe(access)
            assertThat(me.status.value()).isEqualTo(200)
            assertThat(me.responseBody).containsExactlyInAnyOrderEntriesOf(
                mapOf("userId" to loginId, "groupName" to expectedGroupName),
            )
            assertThat(me.responseBody).containsExactlyInAnyOrderEntriesOf(
                mapOf("userId" to login.responseBody!!["userId"], "groupName" to login.responseBody!!["groupName"]),
            )
            assertThat(me.responseHeaders.getCacheControl()).isEqualTo("no-store")
            assertThat(me.responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
        }
        assertThat(sessionValue(loginId)).isEqualTo(jti)
        assertThat(sessionTtlSeconds(loginId)).isBetween(1L, ttl)
    }

    @Test
    fun `O31 O30 NEW 로 만든 계정 — 200 · 로그인 응답의 userId · groupName · 쿠키 · 세션 그대로`() {
        val groupName = uniqueGroupName("Me-New")
        val (owner) = groupOf(groupName, 1)

        assertMe(owner, postLogin(owner), groupName)
    }

    @Test
    fun `O31 EXISTING 으로 들어간 계정 — groupName 은 그룹을 만든 사람의 표기 · 쿠키 · 세션 그대로`() {
        val groupName = uniqueGroupName("Me-Team")
        groupOf(groupName, 1)
        val joiner = uniqueLoginId()
        check(signUp(joiner, groupName.lowercase(), "EXISTING").status.value() == 201)

        assertMe(joiner, postLogin(joiner), groupName)
    }

    @Test
    fun `O32 쿠키 없음은 401`() = getMe().assertUnauthorized()

    @Test
    fun `O32 서명을 1바이트 바꾼 access 는 401`() {
        val (_, login) = loggedIn()
        val (head, payload, sig) = cookieValue(login, AuthCookies.ACCESS).split('.')
        val i = sig.length / 2
        val forged = "$head.$payload.${sig.substring(0, i)}${if (sig[i] == 'A') 'B' else 'A'}${sig.substring(i + 1)}"

        getMe(forged).assertUnauthorized()
    }

    @Test
    fun `O32 만료(시계 +3600초)는 401 이고 +3599초는 200`() {
        val (_, login) = loggedIn()
        val access = cookieValue(login, AuthCookies.ACCESS)

        clock.advance(Duration.ofSeconds(3599))
        assertThat(getMe(access).status.value()).isEqualTo(200)
        clock.advance(Duration.ofSeconds(1))
        getMe(access).assertUnauthorized()
    }

    @Test
    fun `O32 refresh token 을 access 쿠키에 넣으면 401`() {
        val (_, login) = loggedIn()

        getMe(cookieValue(login, AuthCookies.REFRESH)).assertUnauthorized()
    }

    @Test
    fun `O32 Authorization Bearer 만 보내면 401`() {
        val (_, login) = loggedIn()

        getMe(bearer = cookieValue(login, AuthCookies.ACCESS)).assertUnauthorized()
    }

    @Test
    fun `O32 서명은 맞지만 계정이 없는 유저 번호의 access 는 401`() {
        val ghost = Account(UserId(Long.MAX_VALUE), LoginId("ghost_01"), "\$2a\$10\$x", GroupId(Long.MAX_VALUE), GroupName("유령반"))
        val access = tokenIssuer.issue(ghost, "00000000-0000-4000-8000-000000000000", gameProperties.now(clock)).access

        getMe(access).assertUnauthorized()
    }

    @Test
    fun `O33 로그아웃 뒤 지우기 쿠키를 반영한 클라이언트는 401 · 남은 access 를 다시 보내면 exp 까지 200`() {
        val (_, login) = loggedIn()
        val access = cookieValue(login, AuthCookies.ACCESS)

        val logout = postLogout(access, cookieValue(login, AuthCookies.REFRESH))
        assertThat(logout.status.value()).isEqualTo(204)
        // 지우기 쿠키(`access=; Path=/api; Max-Age=0`)를 받은 브라우저는 /api 아래 요청에 access 를 싣지 않는다
        assertThat(setCookie(logout, AuthCookies.ACCESS)).startsWith("access=;").contains("; Path=/api;").contains("; Max-Age=0;")
        val afterLogout = cookieValue(logout, AuthCookies.ACCESS).takeIf { it.isNotEmpty() }
        assertThat(afterLogout).isNull()
        getMe(afterLogout).assertUnauthorized()

        // 검증은 저장소를 보지 않는다 — 지우기 전 토큰은 exp 까지 통과(AS13)
        assertThat(getMe(access).status.value()).isEqualTo(200)
    }

    @Test
    fun `O32 GET me 는 인증 필터의 보호 경로다`() {
        assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.GET, ME)).isTrue()
    }

    private companion object {
        const val ME = "/api/v1/auth/me"
    }
}
