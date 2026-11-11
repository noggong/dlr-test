package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.ClaimedEvents
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.port.EventStreamConsumer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections

/**
 * 오류를 넣는 가짜 포트로 본 persister 소비 루프(ORACLE platform O16 — Redis 오류 · 처리기 `Error` 경로 · LLD platform §3.1).
 * 버킷 하나 · 레인 하나 · 회수할 것 없음.
 */
class StreamPersisterFaultTest {
    /** 버킷 하나짜리 Stream + consumer group. */
    private class FakeStream : EventStreamConsumer {
        private val entries = ArrayList<StreamEvent>()
        private var delivered = 0
        private val pel = sortedMapOf<Long, StreamEvent>()

        @Volatile var loseNextReadReply = false

        @Volatile var failNextAck = false

        @Synchronized fun add(seq: Int) {
            entries += StreamEvent("$seq-0", mapOf("type" to "probe", "seq" to "$seq"))
        }

        @Synchronized fun pending(): Int = pel.size

        override suspend fun ensureGroup(bucket: Int) {}

        override suspend fun readNew(bucket: Int, consumer: String, count: Int): List<StreamEvent> {
            val read = synchronized(this) {
                val next = entries.drop(delivered).take(count)
                delivered += next.size
                next.forEach { pel[it.id.substringBefore('-').toLong()] = it }
                next
            }
            if (read.isNotEmpty() && loseNextReadReply) {
                loseNextReadReply = false
                error("응답을 잃었다(Redis 에서는 배달됨)")
            }
            return read
        }

        override suspend fun readOwnPending(bucket: Int, consumer: String, count: Int): List<StreamEvent> =
            synchronized(this) { pel.values.take(count) }

        override suspend fun claimIdle(bucket: Int, consumer: String, minIdle: Duration, count: Int, cursor: String) =
            ClaimedEvents("0-0", emptyList())

        override suspend fun ack(bucket: Int, ids: List<String>): Long {
            if (failNextAck) {
                failNextAck = false
                error("ACK 실패")
            }
            synchronized(this) { ids.forEach { pel.remove(it.substringBefore('-').toLong()) } }
            return ids.size.toLong()
        }
    }

    /** 처리한 순서를 남기는 처리기 — [faults] 의 seq 는 처음 한 번 그 동작으로 실패한다. */
    private class OrderHandler(private val faults: Map<Int, suspend () -> Unit> = emptyMap()) : PersisterEventHandler {
        override val types = setOf("probe")
        val handled: MutableList<Int> = Collections.synchronizedList(ArrayList())
        private val tripped = Collections.synchronizedSet(HashSet<Int>())

        override suspend fun handle(event: StreamEvent) {
            val seq = event.fields.getValue("seq").toInt()
            faults[seq]?.let { fault -> if (tripped.add(seq)) fault() }
            handled += seq
        }
    }

    private fun persister(stream: FakeStream, handler: OrderHandler) =
        StreamPersister(
            stream,
            listOf(handler),
            PersisterProperties(
                parallelism = 1,
                batchSize = 5,
                idleDelay = Duration.ofMillis(5),
                retryBackoff = Duration.ofMillis(10),
                retryBackoffMax = Duration.ofMillis(20),
            ),
            GameProperties(bucketCount = 1),
        )

    private suspend fun run(stream: FakeStream, handler: OrderHandler, until: suspend () -> Boolean) {
        val persister = persister(stream, handler)
        persister.start()
        try {
            eventually(what = "처리 끝") { until() }
        } finally {
            persister.stop()
        }
    }

    @Test
    fun `O16 읽기 응답을 잃어도 다음 걸음은 자기 PEL 부터라 Stream 순서대로 처리한다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..10).forEach(::add) }
        stream.loseNextReadReply = true
        val handler = OrderHandler()
        run(stream, handler) { handler.handled.size == 10 && stream.pending() == 0 }
        assertThat(handler.handled).containsExactlyElementsOf(1..10)
    }

    @Test
    fun `O16 ACK 가 실패하면 같은 배치를 다시 처리한 뒤에야 다음 메시지로 간다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..10).forEach(::add) }
        stream.failNextAck = true
        val handler = OrderHandler()
        run(stream, handler) { handler.handled.size == 15 && stream.pending() == 0 }
        // 커밋한 1~5 는 ACK 를 못 해 한 번 더 온다(멱등 SQL 몫) — 6 이 그보다 먼저 오지 않는다
        assertThat(handler.handled).containsExactlyElementsOf((1..5) + (1..5) + (6..10))
    }

    @Test
    fun `O16 처리기가 Error 나 자체 타임아웃을 던져도 레인이 살아 그 메시지부터 다시 처리한다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..10).forEach(::add) }
        val handler = OrderHandler(
            mapOf(
                3 to { throw NotImplementedError("처리기 안의 TODO()") },
                7 to { withTimeout(1) { delay(1_000) } },
            ),
        )
        var added = false
        run(stream, handler) {
            // 레인이 살아 있으면 뒤에 온 메시지도 처리한다
            if (!added && handler.handled.size == 10) {
                stream.add(11)
                added = true
            }
            handler.handled.size == 11 && stream.pending() == 0
        }
        assertThat(handler.handled).containsExactlyElementsOf(1..11)
    }
}
