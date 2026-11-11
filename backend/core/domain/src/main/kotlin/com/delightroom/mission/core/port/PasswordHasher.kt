package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.auth.RawPassword

/** 비밀번호 해시(BCrypt cost 10 — LLD auth-signup §1). 구현은 이벤트 루프를 막지 않는다. */
interface PasswordHasher {
    suspend fun hash(raw: RawPassword): String

    suspend fun matches(raw: String, hash: String): Boolean

    /** 아이디가 없을 때 고정 더미 해시와 비교해 응답 시간을 맞춘다(LLD §3.3). */
    suspend fun dummyMatch(raw: String)
}
