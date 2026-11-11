package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.port.DemoClockStore
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

/**
 * `demo:clock` = 런타임 이동량(µs 십진 정수 — LLD platform §4.4). 만료 없음 — AOF 로 재기동 뒤에도 게임 시각이 이어진다.
 * 바꾸기는 읽은 값과 같을 때만 쓰는 작은 Lua 하나다 — 두 이동이 겹쳐도 한쪽만 쓴다.
 */
internal class RedisDemoClockStore(
    private val redis: ReactiveStringRedisTemplate,
    private val keys: RedisKeys,
) : DemoClockStore {
    override suspend fun get(): Long? =
        redis.opsForValue().get(keys.demoClock()).awaitSingleOrNull()?.let { raw ->
            requireNotNull(raw.toLongOrNull()) { "${keys.demoClock()} 값이 정수가 아니다: $raw" }
        }

    override suspend fun setIfAbsent(micros: Long): Boolean =
        redis.opsForValue().setIfAbsent(keys.demoClock(), micros.toString()).awaitSingle()

    override suspend fun compareAndSet(expected: Long, next: Long): Boolean =
        redis.execute(CAS, listOf(keys.demoClock()), listOf(expected.toString(), next.toString())).next().awaitSingle() == 1L

    override suspend fun shortenSessions(by: Duration): Int {
        val ms = by.toMillis()
        if (ms <= 0) return 0
        val options = ScanOptions.scanOptions().match(keys.sessionPattern()).count(1_000).build()
        return redis.scan(options).asFlow().toList().count { key ->
            redis.execute(SHORTEN, listOf(key), listOf(ms.toString())).next().awaitSingle() == 1L
        }
    }

    private companion object {
        /** 남은 TTL − ARGV[1] ms. 남는 것이 없으면 지운다. TTL 이 없는 키는 그대로 둔다. */
        val SHORTEN: RedisScript<Long> = RedisScript.of(
            """
            local t = redis.call('PTTL', KEYS[1])
            if t <= 0 then return 0 end
            local n = t - tonumber(ARGV[1])
            if n <= 0 then redis.call('DEL', KEYS[1]) else redis.call('PEXPIRE', KEYS[1], n) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        val CAS: RedisScript<Long> = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
