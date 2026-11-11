package com.delightroom.mission.app.api.signal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime

/**
 * ORACLE solve-signal O8(명단 없음 · 07:00 전 실패 신호) · O9(07:00 전 풀이 · 패스) — 지금 = 06:58.
 * ORACLE 의 06:59 · 06:59:59 는 컨텍스트를 띄우는 동안 시간이 흐르므로 2분 앞에 둔다(07:00 을 넘지 않게).
 * 초 단위 경계는 `SignalServiceTest` 가 본다. 07:00 이후의 실패 신호 계약은 [PassApiTest].
 */
class FailureApiTest : SignalApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at0658(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(6, 58))
    }

    @Test
    fun `O8 07시 전 명단 없음 — 실패 신호는 409 MISSION_NOT_STARTED · 명단을 만들지 않는다`() {
        assertThat(code(post(FAILURES, access(ME)), 409)).isEqualTo("MISSION_NOT_STARTED")
        assertThat(hash()).isEmpty()
        assertThat(roster()).isEmpty()
    }

    @Test
    fun `O9 07시 전 명단 없음 — 풀이 · 패스는 409 GAME_NOT_OPEN · 명단을 만들지 않는다`() {
        assertThat(code(post(SOLVES, access(ME), solveBody()), 409)).isEqualTo("GAME_NOT_OPEN")
        assertThat(code(post(PASSES, access(ME), passBody()), 409)).isEqualTo("GAME_NOT_OPEN")
        assertThat(roster()).isEmpty()
    }

    @Test
    fun `O8 (바탕) 07시 전에도 본문 검증 · 인증이 먼저다 — 400 · 401`() {
        assertThat(code(post(SOLVES, access(ME), solveBody(no = 0)), 400)).isEqualTo("INVALID_REQUEST")
        assertThat(code(post(FAILURES, null), 401)).isEqualTo("UNAUTHORIZED")
    }
}
