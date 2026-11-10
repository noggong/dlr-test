package com.delightroom.mission.infra.redis.platform

import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** ORACLE platform O9 — Lua 시각 주입 `common.now(offsetUs)` = `TIME + offsetUs`. */
class CommonNowScriptTest {
    private val scripts = RedisScripts(RedisTestContainer.template)

    /** 탐침이 돌려준 (`now`, 그 직후 `TIME`). */
    private fun probe(offsetUs: String): Pair<Long, Long> = runBlocking {
        val reply = scripts.eval("platform_now_probe", emptyList(), listOf(offsetUs))
        reply[0] as Long to reply[1] as Long
    }

    @Test
    fun `O9 offsetUs 0 이면 Redis TIME 그대로`() {
        val (now, time) = probe("0")

        assertThat(time - now).isBetween(0L, 1_000L)
    }

    @Test
    fun `O9 offsetUs 만큼 옮겨진다 — 한 시간 전`() {
        val offsetUs = -3_600_000_000L
        val (now, time) = probe(offsetUs.toString())

        assertThat(time + offsetUs - now).isBetween(0L, 1_000L)
    }

    @Test
    fun `O9 offsetUs 만큼 옮겨진다 — 07시 경계처럼 µs 단위`() {
        val offsetUs = 86_399_999_999L // 하루 − 1µs
        val (now, time) = probe(offsetUs.toString())

        assertThat(time + offsetUs - now).isBetween(0L, 1_000L)
    }

    @Test
    fun `O9 정수가 아닌 offsetUs 는 스크립트 오류`() {
        listOf("", "abc", "1.5", "1e3", "0x10", " 5").forEach { bad ->
            assertThatThrownBy { probe(bad) }.hasStackTraceContaining("offsetUs must be an integer")
        }
    }
}
