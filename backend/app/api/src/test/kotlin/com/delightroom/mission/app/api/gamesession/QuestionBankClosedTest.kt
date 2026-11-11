package com.delightroom.mission.app.api.gamesession

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.LocalTime

/**
 * ORACLE game-session O9 — 08:00 부터 문제은행은 `SUBMISSION_CLOSED`(SPEC §2.2 · GS2 — 08:00 유예 D1 은 풀이 신호에만 있다).
 * 게임 시각은 `D 08:00:00` 에서 시작해 흐른다 — 첫 요청이 08:00:00 + 기동 시간, 두 번째는 그 2초 뒤다.
 * 정확히 08:00:00.000000 경계는 Lua 테스트(StartScriptTest — `deadlineUs = TIME`)가 본다(ORACLE §3).
 */
class QuestionBankClosedTest : QuestionBankApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun offset(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(8, 0))
    }

    @Test
    fun `O9 08시 이후 문제은행은 SUBMISSION_CLOSED — 08시 정각 뒤 · 2초 뒤 모두, 유예 없음`(): Unit = runBlocking {
        val a = groupA()
        val token = access(a, a["alice"])
        assertThat(gameProperties.now(clock)).isAfterOrEqualTo(at(LocalTime.of(8, 0)))

        val first = postBank(token)
        Thread.sleep(Duration.ofSeconds(2))
        val second = postBank(token)

        for (r in listOf(first, second)) {
            assertThat(r.status.value()).isEqualTo(409)
            assertThat(r.responseBody!!["code"]).isEqualTo("SUBMISSION_CLOSED")
        }
        assertThat(hasKey(bankKey(a["alice"]))).isFalse()
        assertThat(events(a)).isEmpty()
    }
}
