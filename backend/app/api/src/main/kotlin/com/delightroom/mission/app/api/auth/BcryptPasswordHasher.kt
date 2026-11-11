package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.domain.auth.RawPassword
import com.delightroom.mission.core.port.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * BCrypt(`$2a$<cost>$` — auth-signup LLD §2.1). cost 10 은 한 번에 약 50~100ms 라 이벤트 루프가 아니라
 * `Dispatchers.Default` 에서 돌린다.
 */
class BcryptPasswordHasher(cost: Int) : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(cost)

    /** [dummyMatch] 가 비교할 해시 — 같은 cost 로 만들어 있는 아이디의 [matches] 와 시간이 같다. */
    private val dummyHash: String = encoder.encode(DUMMY_PASSWORD)!!

    override suspend fun hash(raw: RawPassword): String =
        withContext(Dispatchers.Default) { encoder.encode(raw.value)!! }

    override suspend fun matches(raw: RawPassword, hash: String): Boolean =
        withContext(Dispatchers.Default) { encoder.matches(raw.value, hash) }

    override suspend fun dummyMatch() {
        withContext(Dispatchers.Default) { encoder.matches(DUMMY_COMPARE, dummyHash) }
    }

    private companion object {
        const val DUMMY_PASSWORD = "timing-dummy-password"
        const val DUMMY_COMPARE = "timing-dummy-compare!"
    }
}
