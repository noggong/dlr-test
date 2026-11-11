package com.delightroom.mission.infra.redis.auth

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RefreshTokenRepository
import com.delightroom.mission.infra.redis.RedisInfraAutoConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * LLD auth-signup §2.2 — `sess:{<uid>}` 의 `SET NX` · `sess_rotate.lua` · `sess_end.lua`.
 * API 로 보는 기준(O21 · O22 · I4 · I6)은 T-AU-4 가 판정한다 — 여기서는 저장소 동작만 본다.
 */
class RedisRefreshTokenRepositoryTest {
    private val redis = RedisTestContainer.template
    private val keys = RedisKeys(GameProperties())
    private val sessions: RefreshTokenRepository =
        RedisInfraAutoConfiguration().refreshTokenRepository(redis, RedisScripts(redis), keys)
    private val uid = UserId(900_001)
    private val ttl = Duration.ofSeconds(7200)

    private fun jti() = UUID.randomUUID().toString()

    private suspend fun stored(): String? = redis.opsForValue().get(keys.session(uid)).awaitSingleOrNull()

    private suspend fun ttlSeconds(): Long = redis.getExpire(keys.session(uid)).awaitSingle().seconds

    @AfterEach
    fun clean() = runBlocking<Unit> { redis.delete(keys.session(uid)).awaitSingle() }

    @Test
    fun `start 는 세션이 없을 때만 jti 를 TTL 7200 으로 쓴다`() = runBlocking {
        val first = jti()

        assertThat(sessions.start(uid, first, ttl)).isTrue()
        assertThat(sessions.start(uid, jti(), ttl)).isFalse()
        assertThat(stored()).isEqualTo(first)
        assertThat(ttlSeconds()).isBetween(7190, 7200)
        assertThat(keys.session(uid)).isEqualTo("sess:{900001}")
        Unit
    }

    @Test
    fun `rotate 는 지금 값이 옛 jti 일 때만 새 jti 로 바꾸고 TTL 을 새로 건다`() = runBlocking {
        val old = jti()
        val new = jti()
        sessions.start(uid, old, Duration.ofSeconds(100))

        assertThat(sessions.rotate(uid, old, new, ttl)).isTrue()
        assertThat(stored()).isEqualTo(new)
        assertThat(ttlSeconds()).isBetween(7190, 7200)

        assertThat(sessions.rotate(uid, old, jti(), ttl)).isFalse()
        assertThat(stored()).isEqualTo(new)
        Unit
    }

    @Test
    fun `rotate 는 세션이 없으면(로그아웃 · 만료) 거부하고 키를 만들지 않는다`() = runBlocking {
        assertThat(sessions.rotate(uid, jti(), jti(), ttl)).isFalse()
        assertThat(stored()).isNull()
        Unit
    }

    @Test
    fun `end 는 지금 값이 그 jti 일 때만 지운다`() = runBlocking {
        val live = jti()
        sessions.start(uid, live, ttl)

        assertThat(sessions.end(uid, jti())).isFalse()
        assertThat(stored()).isEqualTo(live)
        assertThat(sessions.end(uid, live)).isTrue()
        assertThat(stored()).isNull()
        assertThat(sessions.end(uid, live)).isFalse()
        assertThat(sessions.start(uid, jti(), ttl)).isTrue()
        Unit
    }

    @Test
    fun `같은 옛 jti 로 동시에 10번 회전하면 하나만 이기고 그 값이 남는다`() = runBlocking {
        val old = jti()
        sessions.start(uid, old, ttl)
        val candidates = List(10) { jti() }

        val results = candidates.map { new -> async(Dispatchers.IO) { new to sessions.rotate(uid, old, new, ttl) } }.awaitAll()

        val winners = results.filter { it.second }.map { it.first }
        assertThat(winners).hasSize(1)
        assertThat(stored()).isEqualTo(winners.single())
        Unit
    }

    @Test
    fun `동시에 10번 start 하면 하나만 연다`() = runBlocking {
        val results = List(10) { async(Dispatchers.IO) { sessions.start(uid, jti(), ttl) } }.awaitAll()

        assertThat(results.count { it }).isEqualTo(1)
        Unit
    }
}
