package com.delightroom.mission.app.api.dto

import java.time.LocalDate
import java.time.OffsetDateTime

enum class GroupMode {
    NEW,
    EXISTING,
}

/** 가입할 때 고르는 그룹 — 새 그룹 만들기(NEW) 또는 기존 그룹 들어가기(EXISTING)(R-G1 · AS4). */
data class GroupChoiceRequest(
    val mode: GroupMode,
    /** 그룹명 — NFC · 앞뒤 공백 제거 뒤 2~20자, 한글 · 영문 · 숫자 · 공백 · `-` · `_`(AS3 · prd D11). */
    val name: String,
)

/** `POST /api/v1/auth/signup` 요청(auth-signup SPEC §2.1). */
data class SignUpRequest(
    /** `^[a-z0-9_]{4,20}$`(AS1). */
    val loginId: String,
    /** 8~64자(AS2). */
    val password: String,
    val group: GroupChoiceRequest,
) {
    /** 평문 비밀번호가 로그 · 예외 메시지에 실리지 않게 한다(ORACLE I8). */
    override fun toString(): String = "SignUpRequest(loginId=$loginId, password=***, group=$group)"
}

/** 가입 `201` — 로그인하지 않는다(쿠키 없음 — AS7). */
data class SignUpResponse(
    val userId: String,
    /** 소속 그룹의 저장된 표기. EXISTING 이면 그룹을 만든 사람의 표기. */
    val groupName: String,
    /** 서버 DB 시계, `+09:00`(AS15). */
    val joinedAt: OffsetDateTime,
    /** 첫 참가 게임 날짜(AS19 · prd D24) — [joinedAt] 의 날짜보다 뒤면 「오늘 미션에는 참여할 수 없다」 안내. */
    val firstGameDate: LocalDate,
)

/** `POST /api/v1/auth/login` 요청 — 형식 검사를 하지 않는다(AS10). */
data class LoginRequest(
    val loginId: String,
    val password: String,
) {
    /** 평문 비밀번호가 로그 · 예외 메시지에 실리지 않게 한다(ORACLE I8). */
    override fun toString(): String = "LoginRequest(loginId=$loginId, password=***)"
}

/** `GET /api/v1/auth/me` `200` — 로그인 응답의 사용자 부분과 같은 키(SPEC §2.5 · prd D23). */
data class MeResponse(
    val userId: String,
    val groupName: String,
)

/** 로그인 · refresh `200` 본문. 토큰은 `Set-Cookie`(access · refresh)로만 간다(AS16). */
data class SessionResponse(
    val userId: String,
    val groupName: String,
    /** access token 만료 = 발급 + 1시간(`+09:00`, 초 단위). */
    val accessExpiresAt: OffsetDateTime,
    /** refresh token 만료 = 발급 + 2시간(`+09:00`, 초 단위). */
    val refreshExpiresAt: OffsetDateTime,
)
