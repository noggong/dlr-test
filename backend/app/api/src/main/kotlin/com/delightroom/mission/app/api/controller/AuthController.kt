package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.LoginRequest
import com.delightroom.mission.app.api.dto.SessionResponse
import com.delightroom.mission.app.api.dto.SignUpRequest
import com.delightroom.mission.app.api.dto.SignUpResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.AuthService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** auth-signup (FR-2 · FR-8 · §5.1) — 계약은 docs/spec/auth-signup.md. 토큰은 HttpOnly 쿠키로만 준다. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {
    /** 가입 `201` — 쿠키 없음. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun signUp(@RequestBody request: SignUpRequest): SignUpResponse {
        // TODO(auth-signup): authService.signUp
        throw NotImplementedYet("auth-signup")
    }

    /** 로그인 `200` — `Set-Cookie` access · refresh. 살아 있는 로그인이 있으면 409 ALREADY_LOGGED_IN. */
    @PostMapping("/login")
    suspend fun login(@RequestBody request: LoginRequest): SessionResponse {
        // TODO(auth-signup): authService.login → 쿠키 2개 · Cache-Control: no-store
        throw NotImplementedYet("auth-signup")
    }

    /** refresh `200` — 쿠키 `refresh` 로 인증, 두 토큰 회전. 본문 없음. */
    @PostMapping("/refresh")
    suspend fun refresh(): SessionResponse {
        // TODO(auth-signup): refresh token 회전
        throw NotImplementedYet("auth-signup")
    }

    /** 로그아웃 `204` — 지우기 쿠키 2개. 실린 refresh 가 살아 있는 것이면 서버 세션 종료. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun logout() {
        // TODO(auth-signup): 세션 종료 · 지우기 쿠키
        throw NotImplementedYet("auth-signup")
    }
}
