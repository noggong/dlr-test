package com.delightroom.mission.infra.redis.deadline

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.result.RankedMember
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.port.ResultCache
import com.delightroom.mission.infra.redis.RedisInfraAutoConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import com.delightroom.mission.infra.redis.ResultCacheCodec
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * ORACLE deadline-result O27 — Lua `result_cache_put`(LLD §4.3)을 직접 부르고, 같은 판정을 `ResultCache.putIfNotOlder` 로도 확인한다.
 * 캐시 값은 `ResultCacheCodec` 이 쓰는 §2.4 JSON 이고, 판정은 그 안의 `date` · `rankingFinal` 만 본다.
 */
class ResultCachePutLuaTest {
    private val redis = RedisTestContainer.template
    private val game = GameProperties()
    private val scripts = RedisScripts(redis)
    private val keys = RedisKeys(game)
    private val cache: ResultCache = RedisInfraAutoConfiguration().resultCache(redis, scripts, keys, game)
    private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    private val key = keys.result(gid)
    private val codec = ResultCacheCodec(game)

    @AfterEach
    fun cleanUp() = runBlocking {
        redis.delete(key).awaitSingle()
        Unit
    }

    private fun result(date: String, final: Boolean, rank: Int = 1) = LatestResult(
        date = LocalDate.parse(date),
        groupStatus = GroupStatus.FAILED,
        decidedAt = OffsetDateTime.parse("${date}T08:00:00.000412+09:00").toInstant(),
        rankingFinal = final,
        ranking = listOf(
            RankedMember(LoginId("amy0"), rank, 3, MemberStatus.SUCCEEDED, Instant.parse("${date}T00:00:00Z"), null),
            RankedMember(LoginId("ben0"), rank + 1, -1, MemberStatus.FAILED, null, null),
        ),
    )

    /** 캐시 값을 Kotlin 이 쓰는 모양(§2.4) 그대로 미리 둔다. */
    private suspend fun seed(value: LatestResult, ttl: Duration) {
        redis.opsForValue().set(key, json(value), ttl).awaitSingle()
    }

    private fun json(value: LatestResult): String = codec.encode(value)

    private suspend fun put(value: LatestResult): Long =
        scripts.eval(
            "result_cache_put",
            listOf(key),
            listOf(value.date.toString(), if (value.rankingFinal) "1" else "0", json(value), game.resultCacheTtl.seconds.toString()),
        ).single() as Long

    private suspend fun raw(): String? = redis.opsForValue().get(key).awaitSingleOrNull()

    private suspend fun ttl(): Long = redis.getExpire(key).awaitSingle().seconds

    @Test
    fun `O27 ① 캐시 11-11(TTL 1,000 남음)에 11-12 를 쓰면 덮는다 · TTL 은 90,000 으로`(): Unit = runBlocking {
        seed(result("2026-11-11", final = true), Duration.ofSeconds(1_000))
        val next = result("2026-11-12", final = false)
        assertThat(put(next)).isEqualTo(1L)
        assertThat(cache.get(gid)).isEqualTo(next)
        assertThat(ttl()).isBetween(89_990L, 90_000L)
    }

    @Test
    fun `O27 ② 캐시 11-12 에 11-11 을 쓰면 그대로`(): Unit = runBlocking {
        seed(result("2026-11-12", final = false), Duration.ofSeconds(90_000))
        val before = raw()
        assertThat(put(result("2026-11-11", final = true))).isEqualTo(0L)
        assertThat(raw()).isEqualTo(before)
    }

    @Test
    fun `O27 ③ 캐시 11-12 확정에 11-12 잠정을 쓰면 그대로`(): Unit = runBlocking {
        seed(result("2026-11-12", final = true), Duration.ofSeconds(90_000))
        val before = raw()
        assertThat(put(result("2026-11-12", final = false, rank = 2))).isEqualTo(0L)
        assertThat(raw()).isEqualTo(before)
    }

    @Test
    fun `O27 ④ 캐시 11-12 잠정에 11-12 확정을 쓰면 확정 값`(): Unit = runBlocking {
        seed(result("2026-11-12", final = false), Duration.ofSeconds(90_000))
        val finalValue = result("2026-11-12", final = true, rank = 2)
        assertThat(put(finalValue)).isEqualTo(1L)
        assertThat(cache.get(gid)).isEqualTo(finalValue)
    }

    @Test
    fun `O27 캐시가 없으면 쓴다 · 같은 날 확정끼리 · 잠정끼리는 덮는다`(): Unit = runBlocking {
        assertThat(put(result("2026-11-12", final = false))).isEqualTo(1L)
        assertThat(ttl()).isBetween(89_990L, 90_000L)
        assertThat(put(result("2026-11-12", final = false, rank = 2))).isEqualTo(1L)
        assertThat(put(result("2026-11-12", final = true))).isEqualTo(1L)
        val again = result("2026-11-12", final = true, rank = 2)
        assertThat(put(again)).isEqualTo(1L)
        assertThat(cache.get(gid)).isEqualTo(again)
    }

    @Test
    fun `O27 읽지 못하는 캐시 값은 없는 것으로 보고 덮는다`(): Unit = runBlocking {
        for (bad in listOf("not json", "[]", """{"rankingFinal":true}""", """{"date":20261112}""")) {
            redis.opsForValue().set(key, bad).awaitSingle()
            val value = result("2026-11-11", final = false)
            assertThat(put(value)).describedAs(bad).isEqualTo(1L)
            assertThat(cache.get(gid)).isEqualTo(value)
        }
    }

    @Test
    fun `O27 ARGV 형식이 틀리면 스크립트 오류 · 캐시 그대로`(): Unit = runBlocking {
        seed(result("2026-11-11", final = false), Duration.ofSeconds(90_000))
        val before = raw()
        val good = listOf("2026-11-12", "1", "{}", "90000")
        for ((i, bad) in listOf(0 to "2026/11/12", 1 to "true", 3 to "0", 3 to "x")) {
            val args = good.toMutableList().also { it[i] = bad }
            assertThatThrownBy { runBlocking { scripts.eval("result_cache_put", listOf(key), args) } }.describedAs("$args")
            assertThat(raw()).isEqualTo(before)
        }
    }

    @Test
    fun `O27 putIfNotOlder 는 같은 판정 — WRITTEN · KEPT`(): Unit = runBlocking {
        val final12 = result("2026-11-12", final = true)
        assertThat(cache.putIfNotOlder(gid, final12)).isEqualTo(ResultCache.PutOutcome.WRITTEN)
        assertThat(cache.putIfNotOlder(gid, result("2026-11-12", final = false, rank = 2))).isEqualTo(ResultCache.PutOutcome.KEPT)
        assertThat(cache.putIfNotOlder(gid, result("2026-11-11", final = true))).isEqualTo(ResultCache.PutOutcome.KEPT)
        assertThat(cache.get(gid)).isEqualTo(final12)
        assertThat(ttl()).isBetween(89_990L, 90_000L)
        val next = result("2026-11-13", final = false)
        assertThat(cache.putIfNotOlder(gid, next)).isEqualTo(ResultCache.PutOutcome.WRITTEN)
        assertThat(cache.get(gid)).isEqualTo(next)
    }

    @Test
    fun `putIfNotOlder 는 Redis 오류면 로그만 — FAILED 를 돌려주고 던지지 않는다`(): Unit = runBlocking {
        val factory = LettuceConnectionFactory("127.0.0.1", 1).also {
            it.afterPropertiesSet()
            it.start()
        }
        try {
            val down = ReactiveStringRedisTemplate(factory)
            val broken = RedisInfraAutoConfiguration().resultCache(down, RedisScripts(down), keys, game)
            assertThat(broken.putIfNotOlder(gid, result("2026-11-12", final = true))).isEqualTo(ResultCache.PutOutcome.FAILED)
        } finally {
            factory.destroy()
        }
    }
}
