package com.delightroom.mission.app.api.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.time.Duration

/** 지금 로그인한 나 — ORACLE auth-signup O31 ~ O33 · O30. 계약은 SPEC §2.5 · AS13 · AS14 · AS18(prd D23). */
class MeApiTest : AuthApiTestSupport() {
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

    @Test
    fun `O31 O30 로그인한 나는 200 · 로그인 응답의 userId · groupName · 쿠키 · 세션 그대로`() {
        val (id, login) = loggedIn()
        val access = cookieValue(login, AuthCookies.ACCESS)
        val jti = sessionValue(id)
        val ttl = sessionTtlSeconds(id)

        val first = getMe(access)
        val second = getMe(access)

        for (me in listOf(first, second)) {
            assertThat(me.status.value()).isEqualTo(200)
            assertThat(me.responseBody).containsExactlyInAnyOrderEntriesOf(
                mapOf("userId" to login.responseBody!!["userId"], "groupName" to login.responseBody!!["groupName"]),
            )
            assertThat(me.responseHeaders.getCacheControl()).isEqualTo("no-store")
            assertThat(me.responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
        }
        assertThat(first.responseBody!!["userId"]).isEqualTo(id)
        assertThat(sessionValue(id)).isEqualTo(jti)
        assertThat(sessionTtlSeconds(id)).isBetween(1L, ttl)
    }

    @Test
    fun `O31 EXISTING 으로 들어간 계정의 groupName 은 그룹을 만든 사람의 표기`() {
        val groupName = uniqueGroupName("Me-Team")
        groupOf(groupName, 1)
        val joiner = uniqueLoginId()
        check(signUp(joiner, groupName.lowercase(), "EXISTING").status.value() == 201)
        val login = postLogin(joiner)

        val me = getMe(cookieValue(login, AuthCookies.ACCESS))

        assertThat(me.status.value()).isEqualTo(200)
        assertThat(me.responseBody).containsExactlyInAnyOrderEntriesOf(mapOf("userId" to joiner, "groupName" to groupName))
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
    fun `O33 로그아웃 뒤 지우기 쿠키를 반영한 클라이언트는 401 · 남은 access 를 다시 보내면 exp 까지 200`() {
        val (_, login) = loggedIn()
        val access = cookieValue(login, AuthCookies.ACCESS)

        val logout = postLogout(access, cookieValue(login, AuthCookies.REFRESH))
        assertThat(logout.status.value()).isEqualTo(204)
        // 지우기 쿠키(`access=; Max-Age=0`)를 받은 브라우저는 access 를 싣지 않는다
        val afterLogout = cookieValue(logout, AuthCookies.ACCESS).takeIf { it.isNotEmpty() }
        assertThat(afterLogout).isNull()
        getMe(afterLogout).assertUnauthorized()

        // 검증은 저장소를 보지 않는다 — 지우기 전 토큰은 exp 까지 통과(AS13)
        assertThat(getMe(access).status.value()).isEqualTo(200)
    }

    @Test
    fun `GET me 는 인증 필터의 보호 경로다`() {
        assertThat(AccessTokenWebFilter.requiresAuth(HttpMethod.GET, ME)).isTrue()
    }

    private companion object {
        const val ME = "/api/v1/auth/me"
    }
}
