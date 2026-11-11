package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.domain.auth.RawPassword
import com.delightroom.mission.core.port.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/** BCrypt(`$2a$<cost>$…` 60자). 해시 · 검증은 CPU 를 쓰므로 `Dispatchers.Default` 에서 — 이벤트 루프를 막지 않는다. */
class BcryptPasswordHasher(cost: Int) : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(cost)

    /** 없는 아이디로 로그인할 때 비교할 고정 해시 — 있는 아이디와 같은 시간을 쓰게 한다. */
    private val dummyHash = encoder.encode("dummy-password-for-timing")!!

    override suspend fun hash(raw: RawPassword): String =
        withContext(Dispatchers.Default) { encoder.encode(raw.value)!! }

    override suspend fun matches(raw: String, hash: String): Boolean =
        withContext(Dispatchers.Default) { encoder.matches(raw, hash) }

    override suspend fun dummyMatch(raw: String) {
        matches(raw, dummyHash)
    }
}
