package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime

/**
 * ORACLE realtime-board O10(API 유예) — 지금을 08:00:01 로 옮긴다. 현황판 조회 창(~08:00:00)은 닫혔지만 티켓 창(~08:00:03)은 열려 있다.
 * 호스트 시계로 오프셋을 잡고 판정은 Redis 컨테이너 `TIME` 이라, 창 끝에서 2초 띄운다. 08:00:02 · 08:00:03 경계 자체는 Lua 테스트가 본다.
 */
class RealtimeTicketApiGraceTest : RealtimeTicketApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at080001(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(8, 0, 1))
    }

    @Test
    fun `O10 08시 00분 01초 — 조회는 409 GAME_NOT_OPEN, 티켓은 201`() {
        fourMembers()
        assertThat(body(getBoard(access(MINJI)), 409)["code"]).isEqualTo("GAME_NOT_OPEN")
        assertThat(body(postTicket(access(MINJI)), 201)["ticket"] as String).matches(TICKET_SHAPE.pattern)
        assertThat(ticketKeys()).hasSize(1)
    }
}
