package com.delightroom.mission.infra.redis.platform

import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisCallback

/** ORACLE platform O10 — lib 붙이기 · `SCRIPT LOAD` → `EVALSHA` · `NOSCRIPT` 재적재. */
class RedisScriptsTest {
    private val redis = RedisTestContainer.template
    private val scripts = RedisScripts(redis, root = "lua-test")

    private suspend fun scriptExists(sha: String): Boolean =
        redis.execute(ReactiveRedisCallback<Boolean> { it.scriptingCommands().scriptExists(listOf(sha)) })
            .awaitSingle()

    /** Redis `commandstats` 의 `SCRIPT LOAD` 누적 호출 수. */
    private suspend fun scriptLoadCalls(): Long {
        val stats = redis.execute(ReactiveRedisCallback { it.serverCommands().info("commandstats") }).awaitSingle()
        val line = stats.getProperty("cmdstat_script|load") ?: return 0
        return line.substringAfter("calls=").substringBefore(',').toLong()
    }

    private suspend fun scriptFlush() {
        redis.execute(ReactiveRedisCallback<String> { it.scriptingCommands().scriptFlush() }).awaitSingle()
    }

    @Test
    fun `O10 lib 두 개를 파일 순서와 반대로 거친 값과 정수 · 안쪽 배열을 돌려준다`() = runBlocking {
        val reply = scripts.eval("chain", listOf("o10:chain"), listOf("x"))

        assertThat(reply).containsExactly("a(z(x))", 42L, listOf("in", 7L))
        assertThat(redis.opsForValue().get("o10:chain").awaitSingle()).isEqualTo("a(z(x))")
        assertThat(scriptExists(scripts.sha("chain"))).isTrue()
        redis.delete("o10:chain").awaitSingle()
        Unit
    }

    @Test
    fun `O10 스칼라 반환은 한 칸 목록`() = runBlocking {
        assertThat(scripts.eval("scalar", emptyList(), emptyList())).containsExactly(5L)
        Unit
    }

    @Test
    fun `O10 SCRIPT FLUSH 뒤에도 같은 호출이 성공하고 다시 올라간다`() = runBlocking {
        val fresh = RedisScripts(redis, root = "lua-test")
        val before = scriptLoadCalls()
        fresh.eval("chain", listOf("o10:flush"), listOf("1"))
        fresh.eval("chain", listOf("o10:flush"), listOf("1"))
        assertThat(scriptLoadCalls() - before).isEqualTo(1)
        scriptFlush()
        assertThat(scriptExists(fresh.sha("chain"))).isFalse()

        val reply = fresh.eval("chain", listOf("o10:flush"), listOf("2"))

        assertThat(reply.first()).isEqualTo("a(z(2))")
        assertThat(scriptExists(fresh.sha("chain"))).isTrue()
        assertThat(scriptLoadCalls() - before).isEqualTo(2)
        redis.delete("o10:flush").awaitSingle()
        Unit
    }

    @Test
    fun `O10 없는 스크립트는 eval 때 · 식별자가 아닌 lib 이름은 로더를 만들 때 예외`() {
        assertThatThrownBy { runBlocking { scripts.eval("nope", emptyList(), emptyList()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { scripts.eval("../x", emptyList(), emptyList()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RedisScripts(redis, root = "lua-badlib") }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("bad-name")
    }

    @Test
    fun `O10 운영 lib 는 common 이고 기본 루트의 스크립트에 붙는다`() = runBlocking {
        val reply = RedisScripts(redis).eval("platform_now_probe", emptyList(), listOf("0"))

        assertThat(reply).hasSize(2).allMatch { it is Long }
        Unit
    }
}
