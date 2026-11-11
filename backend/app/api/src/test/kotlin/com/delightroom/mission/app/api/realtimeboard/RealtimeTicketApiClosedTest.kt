package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime

/**
 * ORACLE realtime-board O10(API 창 밖 1건) — 지금을 08:00:30 으로 옮긴다(호스트 · Redis 컨테이너 시계 차이를 넘게 창 끝에서 띄운다).
 * 명단 · Hash 가 다 있어도 창 밖이 먼저 걸리고, 티켓은 만들지 않는다.
 */
class RealtimeTicketApiClosedTest : RealtimeTicketApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at080030(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(8, 0, 30))
    }

    @Test
    fun `O10 08시 00분 30초 발급은 409 GAME_NOT_OPEN 이고 tk 키 0개`() {
        fourMembers()
        assertThat(body(postTicket(access(MINJI)), 409)["code"]).isEqualTo("GAME_NOT_OPEN")
        assertThat(ticketKeys()).isEmpty()
    }

    @Test
    fun `O10 · RB3 창 밖 + 그룹원 아님은 GAME_NOT_OPEN`() {
        roster(mapOf(MINJI to "minji"))
        assertThat(body(postTicket(access(999L)), 409)["code"]).isEqualTo("GAME_NOT_OPEN")
        assertThat(ticketKeys()).isEmpty()
    }
}
