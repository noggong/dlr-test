package com.delightroom.mission.app.api.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.time.Duration

/** 로그아웃 API — ORACLE auth-signup O25 ~ O27 · O30. 계약은 SPEC §2.4 · AS13 · AS14. */
class LogoutApiTest : AuthApiTestSupport() {
    private fun EntityExchangeResult<ByteArray>.assertCleared() {
        assertThat(status.value()).isEqualTo(204)
        assertThat(responseBody ?: ByteArray(0)).isEmpty()
        assertThat(responseHeaders.getCacheControl()).isEqualTo("no-store")
        assertThat(responseHeaders[HttpHeaders.SET_COOKIE]).containsExactly(
            "access=; Path=/api; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax",
            "refresh=; Path=/api/v1/auth; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax",
        )
    }

    private fun EntityExchangeResult<ByteArray>.assertUnauthorized() {
        assertThat(status.value()).isEqualTo(401)
        assertThat(String(responseBody!!)).contains("\"code\":\"UNAUTHORIZED\"")
        // 인증 필터가 낸 401 에도 AS17 — 캐시 필터가 인증 필터보다 앞이다
        assertThat(responseHeaders.getCacheControl()).isEqualTo("no-store")
        assertThat(responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
    }

    @Test
    fun `O25 O30 로그아웃은 204 · 지우기 쿠키 2개 · 세션 삭제 뒤 바로 다시 로그인할 수 있다`() {
        val (id, login) = loggedIn()

        postLogout(cookieValue(login, AuthCookies.ACCESS), cookieValue(login, AuthCookies.REFRESH)).assertCleared()

        assertThat(sessionValue(id)).isNull()
        // 로그아웃한 refresh token 은 401 · 쿠키 없음(SPEC §2.3 오류 표)
        val refreshed = postRefresh(cookieValue(login, AuthCookies.REFRESH))
        assertThat(refreshed.status.value()).isEqualTo(401)
        assertThat(refreshed.responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
        assertThat(postLogin(id).status.value()).isEqualTo(200)
    }

    @Test
    fun `O25 로그아웃은 여러 번 불러도 204`() {
        val (id, login) = loggedIn()
        val access = cookieValue(login, AuthCookies.ACCESS)
        val refresh = cookieValue(login, AuthCookies.REFRESH)

        postLogout(access, refresh).assertCleared()
        postLogout(access, refresh).assertCleared()

        assertThat(sessionValue(id)).isNull()
    }

    @Test
    fun `O26 access 만 싣고 로그아웃하면 204 이지만 세션은 그대로`() {
        val (id, login) = loggedIn()
        val liveJti = jtiOf(cookieValue(login, AuthCookies.REFRESH))

        postLogout(cookieValue(login, AuthCookies.ACCESS), null).assertCleared()

        assertThat(sessionValue(id)).isEqualTo(liveJti)
    }

    @Test
    fun `O26 회전된 옛 refresh 를 싣고 로그아웃하면 204 이지만 세션은 살아 있는 jti 그대로`() {
        val (id, login) = loggedIn()
        val oldRefresh = cookieValue(login, AuthCookies.REFRESH)
        val rotated = postRefresh(oldRefresh)
        val liveJti = jtiOf(cookieValue(rotated, AuthCookies.REFRESH))

        postLogout(cookieValue(rotated, AuthCookies.ACCESS), oldRefresh).assertCleared()

        assertThat(sessionValue(id)).isEqualTo(liveJti)
    }

    @Test
    fun `O26 다른 계정의 refresh 를 싣고 로그아웃하면 그 계정 세션은 그대로`() {
        val (_, mine) = loggedIn()
        val (otherId, other) = loggedIn()
        val otherJti = jtiOf(cookieValue(other, AuthCookies.REFRESH))

        postLogout(cookieValue(mine, AuthCookies.ACCESS), cookieValue(other, AuthCookies.REFRESH)).assertCleared()

        assertThat(sessionValue(otherId)).isEqualTo(otherJti)
    }

    @Test
    fun `O27 access 쿠키 없이 로그아웃하면 401 이고 세션은 그대로`() {
        val (id, login) = loggedIn()
        val refresh = cookieValue(login, AuthCookies.REFRESH)

        postLogout(null, refresh).assertUnauthorized()

        assertThat(sessionValue(id)).isEqualTo(jtiOf(refresh))
    }

    @Test
    fun `O27 만료된 access 로 로그아웃하면 401 이고 세션은 그대로`() {
        val (id, login) = loggedIn()
        val refresh = cookieValue(login, AuthCookies.REFRESH)
        clock.advance(Duration.ofSeconds(3600))

        postLogout(cookieValue(login, AuthCookies.ACCESS), refresh).assertUnauthorized()

        assertThat(sessionValue(id)).isEqualTo(jtiOf(refresh))
    }

    @Test
    fun `O27 서명을 1바이트 바꾼 access 로 로그아웃하면 401 이고 세션은 그대로`() {
        val (id, login) = loggedIn()
        val refresh = cookieValue(login, AuthCookies.REFRESH)
        val (head, payload, sig) = cookieValue(login, AuthCookies.ACCESS).split('.')
        val i = sig.length / 2
        val forged = "$head.$payload.${sig.substring(0, i)}${if (sig[i] == 'A') 'B' else 'A'}${sig.substring(i + 1)}"

        postLogout(forged, refresh).assertUnauthorized()

        assertThat(sessionValue(id)).isEqualTo(jtiOf(refresh))
    }

    @Test
    fun `O27 refresh token 을 access 쿠키에 넣으면 401`() {
        val (_, login) = loggedIn()
        val refresh = cookieValue(login, AuthCookies.REFRESH)

        postLogout(refresh, refresh).assertUnauthorized()
    }
}
