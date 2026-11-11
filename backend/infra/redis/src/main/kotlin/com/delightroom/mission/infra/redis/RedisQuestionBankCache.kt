package com.delightroom.mission.infra.redis

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
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * `bank:{<uid>}:<date>` — `GET` · `SET … NX PXAT`(LLD game-session §2.1). 값 형식은 [QuestionBankCodec].
 * [putAllIfAbsent] 는 한 연결에 명령을 겹쳐 보낸다 — Lettuce 가 응답을 기다리지 않고 이어 쓰므로 파이프라인이 된다.
 */
internal class RedisQuestionBankCache(
    private val redis: ReactiveStringRedisTemplate,
    private val keys: RedisKeys,
    private val codec: QuestionBankCodec,
) : QuestionBankCache {
    override suspend fun get(userId: UserId, date: LocalDate): QuestionBank? =
        redis.opsForValue().get(keys.bank(userId, date)).awaitSingleOrNull()?.let(codec::decode)

    override suspend fun putIfAbsent(userId: UserId, date: LocalDate, bank: QuestionBank, expireAt: Instant): Boolean =
        putAllIfAbsent(date, mapOf(userId to bank), expireAt) == 1

    override suspend fun putAllIfAbsent(date: LocalDate, banks: Map<UserId, QuestionBank>, expireAt: Instant): Int {
        if (banks.isEmpty()) return 0
        val expiration = Expiration.unixTimestamp(expireAt.toEpochMilli(), TimeUnit.MILLISECONDS)
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
