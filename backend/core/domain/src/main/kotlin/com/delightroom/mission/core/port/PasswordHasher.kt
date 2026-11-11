package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.auth.RawPassword

/**
 * 비밀번호 해시(BCrypt — auth-signup LLD §3.2 · §3.3). 구현은 이벤트 루프를 막지 않는다.
 *
 * 로그인은 형식 검사를 하지 않으므로(LLD §3.1) `RawPassword` 를 만들지 못한 입력은 [dummyMatch] 만 부르고
 * `INVALID_CREDENTIALS` 를 낸다 — 아이디가 없을 때와 같은 길이라 응답 시간으로 갈리지 않는다.
 */
interface PasswordHasher {
    suspend fun hash(raw: RawPassword): String

    /** [hash] 가 BCrypt 해시가 아니면 `false`. */
    suspend fun matches(raw: RawPassword, hash: String): Boolean

    /** 고정 더미 해시와 한 번 비교한다 — 결과는 버린다. 있는 아이디의 [matches] 와 걸리는 시간을 맞춘다. */
    suspend fun dummyMatch()
}
