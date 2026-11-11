package com.delightroom.mission.core.realtimeboard

import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/** LLD realtime-board §2.5 · §4.3 1 — 티켓 값은 `SecureRandom` 32바이트 base64url 43자, 모양 검사는 `^[A-Za-z0-9_-]{43}$`. */
class RealtimeTicketTest {
    @Test
    fun `티켓 값은 43자 base64url 이고 매번 다르다`() {
        val values = List(1_000) { RealtimeTicket.newValue(SecureRandom()) }
        assertThat(values).allSatisfy { assertThat(RealtimeTicket.isWellFormed(it)).isTrue() }
        assertThat(values.toSet()).hasSize(values.size)
        assertThat(RealtimeTicket.isWellFormed("a".repeat(42))).isFalse()
        assertThat(RealtimeTicket.isWellFormed("a".repeat(44))).isFalse()
        assertThat(RealtimeTicket.isWellFormed("a".repeat(42) + "=")).isFalse()
        assertThat(RealtimeTicket.isWellFormed("a".repeat(42) + "+")).isFalse()
    }

    @Test
    fun `toString 에 티켓 값을 싣지 않는다`() {
        val value = RealtimeTicket.newValue(SecureRandom())
        val ticket = RealtimeTicket(value, UserId(1), GroupId(2), LocalDate.of(2026, 11, 12), Instant.EPOCH)
        assertThat(ticket.toString()).doesNotContain(value)
    }
}
