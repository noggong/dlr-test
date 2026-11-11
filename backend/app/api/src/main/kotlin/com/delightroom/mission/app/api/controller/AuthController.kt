package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.app.api.auth.AuthPrincipal
import com.delightroom.mission.app.api.auth.CurrentUser
import com.delightroom.mission.app.api.dto.GroupMode
import com.delightroom.mission.app.api.dto.LoginRequest
import com.delightroom.mission.app.api.dto.SessionResponse
import com.delightroom.mission.app.api.dto.SignUpRequest
import com.delightroom.mission.app.api.dto.SignUpResponse
import com.delightroom.mission.core.domain.auth.IssuedSession
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.service.AuthService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime

/** auth-signup (FR-2 · FR-8 · §5.1) — 계약은 docs/spec/auth-signup.md. 토큰은 HttpOnly 쿠키로만 준다. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val authCookies: AuthCookies,
    private val clock: Clock,
) {
    /** 가입 `201` — 쿠키 없음. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun signUp(@RequestBody request: SignUpRequest): SignUpResponse {
        val mode = when (request.group.mode) {
            GroupMode.NEW -> GroupChoice.Mode.NEW
            GroupMode.EXISTING -> GroupChoice.Mode.EXISTING
        }
        val signedUp = authService.signUp(request.loginId, request.password, mode, request.group.name)
        return SignUpResponse(
            userId = signedUp.loginId.value,
            groupName = signedUp.groupName.value,
            // DB 시계 그대로, 서버 시간대(+09:00) 오프셋으로(SPEC AS15)
            joinedAt = OffsetDateTime.ofInstant(signedUp.joinedAt, clock.zone),
        )
    }

    /** 로그인 `200` — `Set-Cookie` access · refresh. 살아 있는 로그인이 있으면 409 ALREADY_LOGGED_IN. */
    @PostMapping("/login")
    suspend fun login(@RequestBody request: LoginRequest, response: ServerHttpResponse): SessionResponse =
        respond(authService.login(request.loginId, request.password), response)

    /**
     * refresh `200` — 쿠키 `refresh` 로 인증, 두 토큰 회전(본문은 보지 않는다).
     * 401 은 `Set-Cookie` 를 주지 않는다 — 다른 탭이 이미 받은 새 쿠키를 지우지 않는다(SPEC AS12).
     */
    @PostMapping("/refresh")
    suspend fun refresh(
        @CookieValue(AuthCookies.REFRESH, required = false) refreshToken: String?,
        response: ServerHttpResponse,
    ): SessionResponse = respond(authService.refresh(refreshToken), response)

    /**
     * 로그아웃 `204` — 지우기 쿠키 2개. 실린 refresh 가 이 계정의 살아 있는 것이면 서버 세션 종료(SPEC AS13).
     * access 검증은 인증 필터가 한다 — 쿠키 없음 · 위조 · 만료 · refresh 모양이면 여기까지 오지 않고 401(SPEC AS14).
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun logout(
        @CurrentUser principal: AuthPrincipal,
        @CookieValue(AuthCookies.REFRESH, required = false) refreshToken: String?,
        response: ServerHttpResponse,
    ) {
        authService.logout(principal.userId, refreshToken)
        authCookies.clear().forEach { response.headers.add(HttpHeaders.SET_COOKIE, it) }
    }

    /** 쿠키 2개를 붙이고 본문을 만든다 — 만료 시각은 토큰 `exp` 그대로, 서버 시간대(+09:00) 오프셋으로. */
    private fun respond(session: IssuedSession, response: ServerHttpResponse): SessionResponse {
        authCookies.issue(session.tokens).forEach { response.headers.add(HttpHeaders.SET_COOKIE, it) }
        return SessionResponse(
            userId = session.loginId.value,
            groupName = session.groupName.value,
            accessExpiresAt = OffsetDateTime.ofInstant(session.tokens.accessExpiresAt, clock.zone),
            refreshExpiresAt = OffsetDateTime.ofInstant(session.tokens.refreshExpiresAt, clock.zone),
        )
    }
}
