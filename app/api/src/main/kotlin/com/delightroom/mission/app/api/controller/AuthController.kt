package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.LoginRequest
import com.delightroom.mission.app.api.dto.SignUpRequest
import com.delightroom.mission.app.api.dto.SignUpResponse
import com.delightroom.mission.app.api.dto.TokenResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.AuthService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** auth-signup (FR-2 · FR-8 · §5.1). */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun signUp(@RequestBody request: SignUpRequest): SignUpResponse {
        // TODO(auth-signup): authService.signUp
        throw NotImplementedYet("auth-signup")
    }

    @PostMapping("/login")
    suspend fun login(@RequestBody request: LoginRequest): TokenResponse {
        // TODO(auth-signup): authService.login
        throw NotImplementedYet("auth-signup")
    }
}
