package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime

/** ORACLE realtime-board O3 (API 창 밖 1건) · O7 — 지금을 08:00:01 로 옮긴다. 명단 · Hash 가 다 있어도 창 밖이 먼저 걸린다. */
class BoardApiClosedTest : BoardApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at080001(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(8, 0, 1))
    }

    @Test
    fun `O3 08시 00분 01초 조회는 409 GAME_NOT_OPEN`() {
        fourMembers()
        getBoard(access(MINJI)).expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("GAME_NOT_OPEN")
    }

    @Test
    fun `O7 창 밖 + 그룹원 아님 + 1명 그룹은 GAME_NOT_OPEN`() {
        roster(mapOf(MINJI to "minji"))
        getBoard(access(999L)).expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("GAME_NOT_OPEN")
    }
}
