package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.domain.auth.IssuedTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** SPEC auth-signup §2 쿠키 표 — 속성 · 순서 · `Expires` = 토큰 `exp`. 응답 전체로는 T-AU-4 의 O15 · O25 가 본다. */
class AuthCookiesTest {
    private val properties = AuthProperties(jwtSecret = "mission-local-jwt-secret-not-for-production")
    private val tokens = IssuedTokens(
        access = "a.b.c",
        accessExpiresAt = Instant.parse("2026-11-11T22:40:00Z"),
        refresh = "d.e.f",
        refreshExpiresAt = Instant.parse("2026-11-11T23:40:00Z"),
    )

    @Test
    fun `발급 쿠키 두 개는 SPEC 예시와 글자 그대로 같다`() {
        assertThat(AuthCookies(properties).issue(tokens)).containsExactly(
            "access=a.b.c; Path=/api; Max-Age=3600; Expires=Wed, 11 Nov 2026 22:40:00 GMT; Secure; HttpOnly; SameSite=Lax",
            "refresh=d.e.f; Path=/api/v1/auth; Max-Age=7200; Expires=Wed, 11 Nov 2026 23:40:00 GMT; Secure; HttpOnly; SameSite=Lax",
        )
    }

    @Test
    fun `지우기 쿠키 두 개는 SPEC 예시와 글자 그대로 같다`() {
        assertThat(AuthCookies(properties).clear()).containsExactly(
            "access=; Path=/api; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax",
            "refresh=; Path=/api/v1/auth; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax",
        )
    }

    @Test
    fun `AUTH_COOKIE_SECURE=false 면 Secure 만 빠진다`() {
        val cookies = AuthCookies(properties.copy(cookieSecure = false))

        assertThat(cookies.issue(tokens).first())
            .isEqualTo("access=a.b.c; Path=/api; Max-Age=3600; Expires=Wed, 11 Nov 2026 22:40:00 GMT; HttpOnly; SameSite=Lax")
        assertThat(cookies.clear()).noneMatch { it.contains("Secure") }
    }
}
