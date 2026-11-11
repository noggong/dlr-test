package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.domain.auth.RawPassword
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** LLD auth-signup §2.1 · §3.3 — `$2a$10$` 60자 해시 · 검증 · 더미 비교. */
class BcryptPasswordHasherTest {
    private val hasher = BcryptPasswordHasher(10)

    @Test
    fun `해시는 cost 10 의 2a 형식 60자이고 같은 비밀번호도 매번 다르다`() = runBlocking {
        val raw = RawPassword("correct-horse-9")
        val first = hasher.hash(raw)
        val second = hasher.hash(raw)

        assertThat(first).startsWith("\$2a\$10\$").hasSize(60).doesNotContain(raw.value)
        assertThat(second).isNotEqualTo(first)
        assertThat(hasher.matches(raw, first)).isTrue()
        assertThat(hasher.matches(raw, second)).isTrue()
        Unit
    }

    @Test
    fun `틀린 비밀번호 · BCrypt 가 아닌 해시는 false`() = runBlocking {
        val hash = hasher.hash(RawPassword("correct-horse-9"))

        assertThat(hasher.matches(RawPassword("correct-horse-8"), hash)).isFalse()
        assertThat(hasher.matches(RawPassword("correct-horse-9"), "plain")).isFalse()
        assertThat(hasher.matches(RawPassword("correct-horse-9"), "")).isFalse()
        Unit
    }

    @Test
    fun `64자 ASCII 비밀번호(BCrypt 72바이트 한도 안)도 해시 · 검증한다`() = runBlocking {
        val raw = RawPassword("~".repeat(64))

        assertThat(hasher.matches(raw, hasher.hash(raw))).isTrue()
        Unit
    }

    @Test
    fun `dummyMatch 는 예외 없이 끝난다`() = runBlocking {
        hasher.dummyMatch()
    }
}
