package com.delightroom.mission.app.api.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.time.Duration

/** refresh API — ORACLE auth-signup O21 ~ O24 · O30. 계약은 SPEC §2.3 · AS11 · AS12. */
class RefreshApiTest : AuthApiTestSupport() {
    private fun EntityExchangeResult<Map<String, Any>>.assertUnauthorized() {
        assertThat(status.value()).describedAs("본문 %s", responseBody).isEqualTo(401)
        assertThat(responseBody!!["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(responseHeaders.getCacheControl()).isEqualTo("no-store")
        // AS12 — 401 은 쿠키를 건드리지 않는다
        assertThat(responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
    }

    private fun EntityExchangeResult<Map<String, Any>>.assertOk() {
        assertThat(status.value()).describedAs("본문 %s", responseBody).isEqualTo(200)
    }

    @Test
    fun `O21 O30 refresh 는 두 토큰을 새로 주고 세션을 새 jti 로 회전한다`() {
        setClock("2026-11-12T06:40:00+09:00")
        val (id, login) = loggedIn()
        val oldAccess = cookieValue(login, AuthCookies.ACCESS)
        val oldRefresh = cookieValue(login, AuthCookies.REFRESH)
        clock.advance(Duration.ofMinutes(55))

        val result = postRefresh(oldRefresh)

        result.assertOk()
        assertThat(result.responseHeaders.getCacheControl()).isEqualTo("no-store")
        val body = result.responseBody!!
        assertThat(body.keys).containsExactlyInAnyOrder("userId", "groupName", "accessExpiresAt", "refreshExpiresAt")
        assertThat(body["userId"]).isEqualTo(id)
        assertThat(body["groupName"]).isEqualTo(login.responseBody!!["groupName"])
        assertThat(body["accessExpiresAt"]).isEqualTo("2026-11-12T08:35:00+09:00")
        assertThat(body["refreshExpiresAt"]).isEqualTo("2026-11-12T09:35:00+09:00")

        val newAccess = cookieValue(result, AuthCookies.ACCESS)
        val newRefresh = cookieValue(result, AuthCookies.REFRESH)
        assertThat(newAccess).isNotEqualTo(oldAccess)
        assertThat(newRefresh).isNotEqualTo(oldRefresh)
        assertThat(setCookie(result, AuthCookies.ACCESS)!!.split("; ").drop(1)).containsExactly(
            "Path=/api", "Max-Age=3600", "Expires=Wed, 11 Nov 2026 23:35:00 GMT", "Secure", "HttpOnly", "SameSite=Lax",
        )
        assertThat(setCookie(result, AuthCookies.REFRESH)!!.split("; ").drop(1)).containsExactly(
            "Path=/api/v1/auth", "Max-Age=7200", "Expires=Thu, 12 Nov 2026 00:35:00 GMT", "Secure", "HttpOnly", "SameSite=Lax",
        )
        assertThat(sessionValue(id)).isEqualTo(jtiOf(newRefresh))
        assertThat(sessionTtlSeconds(id)).isBetween(7190, 7200)
    }

    @Test
    fun `O22 O30 회전된 옛 refresh token 은 401 이고 쿠키 · 세션을 건드리지 않는다`() {
        val (id, login) = loggedIn()
        val oldRefresh = cookieValue(login, AuthCookies.REFRESH)
        val rotated = postRefresh(oldRefresh).also { it.assertOk() }
        val liveJti = jtiOf(cookieValue(rotated, AuthCookies.REFRESH))

        postRefresh(oldRefresh).assertUnauthorized()

        assertThat(sessionValue(id)).isEqualTo(liveJti)
    }

    @Test
    fun `O23 refresh 쿠키 없음은 401`() {
        postRefresh(null).assertUnauthorized()
    }

    @Test
    fun `O23 서명을 1바이트 바꾼 refresh token 은 401`() {
        val (id, login) = loggedIn()
        val refresh = cookieValue(login, AuthCookies.REFRESH)
        val (head, payload, sig) = refresh.split('.')
        val i = sig.length / 2
        val tampered = "$head.$payload.${sig.substring(0, i)}${if (sig[i] == 'A') 'B' else 'A'}${sig.substring(i + 1)}"

        postRefresh(tampered).assertUnauthorized()

        assertThat(sessionValue(id)).isEqualTo(jtiOf(refresh))
    }

    @Test
    fun `O23 발급 7200초 뒤 refresh token 은 401`() {
        val (_, login) = loggedIn()
        clock.advance(Duration.ofSeconds(7200))

        postRefresh(cookieValue(login, AuthCookies.REFRESH)).assertUnauthorized()
    }

    @Test
    fun `O23 access token 을 refresh 쿠키에 넣으면 401`() {
        val (_, login) = loggedIn()

        postRefresh(cookieValue(login, AuthCookies.ACCESS)).assertUnauthorized()
    }

    @Test
    fun `O24 100분마다 refresh 하면 최초 로그인 300분 뒤에도 보호된 api 가 401 이 아니다`() {
        val (_, login) = loggedIn()
        var refresh = cookieValue(login, AuthCookies.REFRESH)
        var access = cookieValue(login, AuthCookies.ACCESS)
        repeat(3) {
            clock.advance(Duration.ofMinutes(100))
            val result = postRefresh(refresh)
            result.assertOk()
            refresh = cookieValue(result, AuthCookies.REFRESH)
            access = cookieValue(result, AuthCookies.ACCESS)
        }

        val status = client.get().uri("/api/v1/games/today").cookie(AuthCookies.ACCESS, access)
            .exchange().returnResult(String::class.java).status.value()

        assertThat(status).isNotEqualTo(401)
    }
}
