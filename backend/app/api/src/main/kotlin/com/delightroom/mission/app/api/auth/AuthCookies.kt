package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.domain.auth.IssuedTokens
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `access` · `refresh` 쿠키의 `Set-Cookie` 값(auth-signup SPEC §2 쿠키 표).
 *
 * Spring `ResponseCookie` 는 `Expires` 를 시스템 시계 + `Max-Age` 로 쓴다 — 앱의 `Clock` · `game.clock-offset` 과
 * 어긋나므로 `Expires` 를 토큰 `exp`(= 본문 `accessExpiresAt` · `refreshExpiresAt`)로 직접 적는다.
 * 속성 순서는 SPEC 예시와 같다: `Path` · `Max-Age` · `Expires` · `Secure` · `HttpOnly` · `SameSite`.
 */
class AuthCookies(private val properties: AuthProperties) {
    /** 로그인 · refresh `200` 의 두 쿠키. */
    fun issue(tokens: IssuedTokens): List<String> =
        listOf(
            cookie(ACCESS, tokens.access, ACCESS_PATH, properties.accessTtl, tokens.accessExpiresAt),
            cookie(REFRESH, tokens.refresh, REFRESH_PATH, properties.refreshTtl, tokens.refreshExpiresAt),
        )

    /** 로그아웃 `204` 의 지우기 쿠키 두 개(값 빈 문자열 · `Max-Age=0` · 1970 `Expires`). */
    fun clear(): List<String> =
        listOf(
            cookie(ACCESS, "", ACCESS_PATH, Duration.ZERO, Instant.EPOCH),
            cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO, Instant.EPOCH),
        )

    private fun cookie(name: String, value: String, path: String, maxAge: Duration, expires: Instant): String =
        buildString {
            append(name).append('=').append(value)
            append("; Path=").append(path)
            append("; Max-Age=").append(maxAge.seconds)
            append("; Expires=").append(HTTP_DATE.format(expires))
            if (properties.cookieSecure) append("; Secure")
            append("; HttpOnly; SameSite=Lax")
        }

    companion object {
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val ACCESS_PATH = "/api"
        const val REFRESH_PATH = "/api/v1/auth"

        /** RFC 7231 IMF-fixdate — 일은 두 자리(`01 Jan`). `RFC_1123_DATE_TIME` 은 한 자리라 쓰지 않는다. */
        private val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
    }
}
