package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RefreshTokenRepository
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

/**
 * `sess:{<uid>}` = 살아 있는 refresh token 의 `jti`(auth-signup LLD §2.2).
 * 로그인은 `SET NX EX` 한 번, 회전 · 로그아웃은 Lua 로 비교와 쓰기를 한 번에 한다. 키 하나만 만진다.
 */
internal class RedisRefreshTokenRepository(
    private val redis: ReactiveStringRedisTemplate,
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
) : RefreshTokenRepository {
    override suspend fun start(userId: UserId, jti: String, ttl: Duration): Boolean =
        redis.opsForValue().setIfAbsent(keys.session(userId), jti, ttl.checkSeconds()).awaitSingle()

    override suspend fun rotate(userId: UserId, oldJti: String, newJti: String, ttl: Duration): Boolean =
        scripts.eval(
            "sess_rotate",
            listOf(keys.session(userId)),
            listOf(oldJti, newJti, ttl.checkSeconds().seconds.toString()),
        ).single() == 1L

    override suspend fun end(userId: UserId, jti: String): Boolean =
        scripts.eval("sess_end", listOf(keys.session(userId)), listOf(jti)).single() == 1L

    /** `EX` 는 초 단위 양수만 받는다 — 초 아래가 있으면 잘리지 않게 거부한다. */
    private fun Duration.checkSeconds(): Duration {
        require(seconds > 0 && nano == 0) { "세션 TTL 은 양의 초 단위다: $this" }
        return this
    }
}
