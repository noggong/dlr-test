package com.delightroom.mission.app.api.dto

import java.time.Instant

enum class GroupMode {
    NEW,
    EXISTING,
}

/** 가입할 때 고르는 그룹 — 새 그룹(그룹명 지정) 또는 기존 그룹(그룹명 입력)(R-G1). */
data class GroupChoiceRequest(
    val mode: GroupMode,
    val name: String,
)

data class SignUpRequest(
    val loginId: String,
    val password: String,
    val group: GroupChoiceRequest,
)

data class SignUpResponse(
    val userId: String,
    val groupName: String,
    val joinedAt: Instant,
)

data class LoginRequest(
    val loginId: String,
    val password: String,
)

data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Instant,
)
