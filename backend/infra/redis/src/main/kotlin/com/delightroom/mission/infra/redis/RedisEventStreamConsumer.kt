package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.event.ClaimedEvents
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.port.EventStreamConsumer
import java.time.Duration

/**
 * persister 소비 포트의 Redis 구현(LLD platform §3.1). Spring Data Redis 에 `XAUTOCLAIM` 이 없어 Stream 명령은
 * 모두 [RedisScripts] 의 Lua `persister_*` 로 돈다. 읽기에 `BLOCK` 을 쓰지 않는다 — 공유 연결을 막지 않는다.
 */
internal class RedisEventStreamConsumer(
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
) : EventStreamConsumer {
    override suspend fun ensureGroup(bucket: Int) {
        scripts.eval("persister_group", listOf(keys.events(bucket)), listOf(GROUP))
    }

    override suspend fun readNew(bucket: Int, consumer: String, count: Int): List<StreamEvent> =
        read(bucket, consumer, count, ">")

    override suspend fun readOwnPending(bucket: Int, consumer: String, count: Int): List<StreamEvent> =
        read(bucket, consumer, count, "0")

    override suspend fun claimIdle(
        bucket: Int,
        consumer: String,
        minIdle: Duration,
        count: Int,
        cursor: String,
    ): ClaimedEvents {
        val reply = scripts.eval(
            "persister_claim",
            listOf(keys.events(bucket)),
            listOf(GROUP, consumer, minIdle.toMillis().toString(), cursor, count.toString()),
        )
        check(reply.size == 2) { "persister_claim 반환은 두 칸이다: $reply" }
        return ClaimedEvents(reply[0] as String, entries(reply[1]))
    }

    override suspend fun ack(bucket: Int, ids: List<String>): Long {
        if (ids.isEmpty()) return 0
        val reply = scripts.eval("persister_ack", listOf(keys.events(bucket)), listOf(GROUP) + ids)
        return reply.single() as Long
    }

    private suspend fun read(bucket: Int, consumer: String, count: Int, from: String): List<StreamEvent> {
        require(count > 0) { "COUNT 는 1 이상이다: $count" }
        val reply = scripts.eval(
            "persister_read",
            listOf(keys.events(bucket)),
            listOf(GROUP, consumer, count.toString(), from),
        )
        return entries(reply)
    }

    private fun entries(raw: Any): List<StreamEvent> =
        (raw as List<*>).map { entry ->
            val (id, flat) = entry as List<*>
            val values = flat as List<*>
            check(values.size % 2 == 0) { "Stream 칸 목록이 짝이 맞지 않는다: $id" }
            StreamEvent(
                id = id as String,
                fields = values.chunked(2).associate { (k, v) -> k as String to v as String },
            )
        }

    private companion object {
        /** consumer group 이름 — HLD platform §4.2 · LLD platform §3.1. */
        const val GROUP = "persister"
    }
}
