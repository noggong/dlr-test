package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.QuestionBankCache
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.connection.RedisStringCommands.SetOption
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.types.Expiration
import reactor.core.publisher.Flux
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * `bank:{<uid>}:<date>` — `GET` · `SET … NX PX <남은 ms>`(LLD game-session v6 §2.1). 값 형식은 [QuestionBankCodec].
 * 만료는 절대 시각(`PXAT`)이 아니라 게임 시각 [GameClock.now] 부터 `expireAt` 까지 남은 ms 다 — Redis 는 `game.clock-offset` 을 모른다.
 * [putAllIfAbsent] 는 한 연결에 명령을 겹쳐 보낸다 — Lettuce 가 응답을 기다리지 않고 이어 쓰므로 파이프라인이 된다.
 */
internal class RedisQuestionBankCache(
    private val redis: ReactiveStringRedisTemplate,
    private val keys: RedisKeys,
    private val codec: QuestionBankCodec,
    private val gameClock: GameClock,
) : QuestionBankCache {
    override suspend fun get(userId: UserId, date: LocalDate): QuestionBank? =
        redis.opsForValue().get(keys.bank(userId, date)).awaitSingleOrNull()?.let(codec::decode)

    override suspend fun putIfAbsent(userId: UserId, date: LocalDate, bank: QuestionBank, expireAt: Instant): Boolean =
        putAllIfAbsent(date, mapOf(userId to bank), expireAt) == 1

    override suspend fun putAllIfAbsent(date: LocalDate, banks: Map<UserId, QuestionBank>, expireAt: Instant): Int {
        if (banks.isEmpty()) return 0
        // 08:00 직전 시계 차이로 0 이하가 되면 1 ms — 곧 사라지지만 「없을 때만」은 그대로 지킨다
        val ttlMs = Duration.between(gameClock.now(), expireAt).toMillis().coerceAtLeast(1L)
        val expiration = Expiration.from(ttlMs, TimeUnit.MILLISECONDS)
        return redis.execute { connection ->
            Flux.fromIterable(banks.entries).flatMapSequential(
                { (uid, bank) ->
                    connection.stringCommands().set(
                        UTF_8.encode(keys.bank(uid, date)),
                        ByteBuffer.wrap(codec.encode(bank).toByteArray(UTF_8)),
                        expiration,
                        SetOption.ifAbsent(),
                    )
                },
                // 묶음 전체를 응답을 기다리지 않고 보낸다
                banks.size,
            )
        }.filter { it }.count().awaitSingle().toInt()
    }
}
