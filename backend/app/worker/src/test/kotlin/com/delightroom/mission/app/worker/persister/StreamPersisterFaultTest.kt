package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.ClaimedEvents
import com.delightroom.mission.core.domain.event.DeadLetter
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.port.DeadLetterStore
import com.delightroom.mission.core.port.EventStreamConsumer
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 오류를 넣는 가짜 포트로 본 persister 소비 루프(ORACLE platform O16 — Redis 오류 · 처리기 `Error` 경로 · O20 ⓑ · O21 — dead-letter 와
 * DB 장애 · LLD platform §3.1). 버킷 하나 · 레인 하나 · 회수할 것 없음.
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

        @Synchronized fun pendingSeqs(): List<Long> = pel.keys.toList()

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

    /** 가짜 dead-letter 저장소 — [failPuts] 번은 DB 장애로 실패하고, [onPut] 은 쌓은 직후 불린다. */
    @Volatile private var ackFailed = false

    private class FakeDeadLetters(failPuts: Int = 0, private val onPut: () -> Unit = {}) : DeadLetterStore {
        private val failing = AtomicInteger(failPuts)
        val puts = AtomicInteger()

        fun recover() = failing.set(0)
        val byKey = ConcurrentHashMap<Pair<Int, String>, DeadLetter>()

        override suspend fun put(letter: DeadLetter) {
            if (failing.getAndDecrement() > 0) throw DataAccessResourceFailureException("주입한 DB 장애")
            puts.incrementAndGet()
            byKey[letter.bucket to letter.streamId] = letter
            onPut()
        }
    }

    /** seq 와 그 seq 의 시도 번호(1 부터)로 던질 예외를 고르는 처리기 — null 이면 성공. */
    private class ScriptedHandler(private val script: (seq: Int, attempt: Int) -> Throwable?) : PersisterEventHandler {
        override val types = setOf("probe")
        val handled: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val attempts = ConcurrentHashMap<Int, AtomicInteger>()

        override suspend fun handle(event: StreamEvent) {
            val seq = event.fields.getValue("seq").toInt()
            val attempt = attempts.computeIfAbsent(seq) { AtomicInteger() }.incrementAndGet()
            script(seq, attempt)?.let { throw it }
            handled += seq
        }
    }

    private fun persister(
        stream: FakeStream,
        handler: PersisterEventHandler,
        deadLetters: DeadLetterStore = FakeDeadLetters(),
    ) =
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
            deadLetters,
            MutableClock(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC),
        )

    private suspend fun run(
        stream: FakeStream,
        handler: PersisterEventHandler,
        deadLetters: DeadLetterStore = FakeDeadLetters(),
        until: suspend () -> Boolean,
    ) {
        val persister = persister(stream, handler, deadLetters)
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

    @Test
    fun `O20 dead-letter 를 쓴 뒤 ACK 가 실패하면 다시 받아 다시 쌓아도 한 건이고 ACK 된다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..5).forEach(::add) }
        // 3 은 늘 센 실패 — 처음 쌓은 직후의 ACK 만 실패시킨다
        val deadLetters = FakeDeadLetters(onPut = { if (!ackFailed) { ackFailed = true; stream.failNextAck = true } })
        val handler = ScriptedHandler { seq, _ -> if (seq == 3) IllegalStateException("처리기 버그") else null }
        run(stream, handler, deadLetters) { deadLetters.puts.get() == 2 && stream.pending() == 0 }
        assertThat(deadLetters.byKey.keys).containsExactly(0 to "3-0")
        assertThat(deadLetters.byKey.values.single().failureCount).isEqualTo(3)
        assertThat(handler.attempts.getValue(3).get()).isEqualTo(6) // 3번 세고 쌓기 · ACK 를 잃어 다시 3번
        // ACK 를 잃은 배치(3 · 4 · 5)의 4 · 5 는 한 번 더 온다(멱등 SQL 몫) — 순서는 그대로
        assertThat(handler.handled).containsExactly(1, 2, 4, 5, 4, 5)
    }

    @Test
    fun `O21 DB 연결 장애는 세지 않아 dead-letter 로 가지 않고 ACK 하지 않는다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..5).forEach(::add) }
        val deadLetters = FakeDeadLetters()
        val dbDown = AtomicBoolean(true)
        // 장애 동안 3 은 홀수 번째엔 그대로, 짝수 번째엔 원인 사슬 안에 DataAccessResourceFailureException
        val handler = ScriptedHandler { seq, attempt ->
            when {
                seq != 3 || !dbDown.get() -> null
                attempt % 2 == 1 -> DataAccessResourceFailureException("커넥션 끊김")
                else -> IllegalStateException("감싼 실패", DataAccessResourceFailureException("커넥션 끊김"))
            }
        }
        val persister = persister(stream, handler, deadLetters)
        persister.start()
        try {
            eventually(what = "3 을 문턱(3)보다 많이 시도") { (handler.attempts[3]?.get() ?: 0) >= 6 }
            assertThat(handler.handled).containsExactly(1, 2)
            assertThat(stream.pendingSeqs()).containsExactly(3L, 4L, 5L)
            assertThat(deadLetters.puts.get()).isZero()
            dbDown.set(false)
            eventually(what = "처리 끝") { handler.handled.size == 5 && stream.pending() == 0 }
        } finally {
            persister.stop()
        }
        assertThat(handler.handled).containsExactly(1, 2, 3, 4, 5)
        assertThat(deadLetters.byKey).isEmpty()
    }

    @Test
    fun `O21 dead-letter 쓰기가 DB 장애로 실패하면 ACK 하지 않고 다음 시도에서 다시 쌓는다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..5).forEach(::add) }
        val deadLetters = FakeDeadLetters(failPuts = Int.MAX_VALUE)
        val handler = ScriptedHandler { seq, _ -> if (seq == 3) IllegalStateException("처리기 버그") else null }
        val persister = persister(stream, handler, deadLetters)
        persister.start()
        try {
            // 3번째 시도에서 문턱 — 그 뒤 쌓기가 계속 실패하는 동안
            eventually(what = "쌓기 실패 여러 번") { (handler.attempts[3]?.get() ?: 0) >= 5 }
            assertThat(handler.handled).containsExactly(1, 2)
            assertThat(stream.pendingSeqs()).containsExactly(3L, 4L, 5L)
            assertThat(deadLetters.byKey).isEmpty()
            deadLetters.recover()
            eventually(what = "처리 끝") { handler.handled.size == 4 && stream.pending() == 0 }
        } finally {
            persister.stop()
        }
        assertThat(handler.handled).containsExactly(1, 2, 4, 5)
        val letter = deadLetters.byKey.values.single()
        assertThat(letter.streamId).isEqualTo("3-0")
        // 쌓기에 실패한 시도도 센 수에 남는다 — 쌓인 수 = 그때까지 시도한 수
        assertThat(letter.failureCount).isEqualTo(handler.attempts.getValue(3).get()).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `O21 센 실패 사이에 일시 장애가 끼어도 센 수는 지워지지 않는다`(): Unit = runBlocking {
        val stream = FakeStream().apply { (1..4).forEach(::add) }
        val deadLetters = FakeDeadLetters()
        // 3 은 센 · 일시 장애 · 센 · 센 — 4번째 시도에서 센 수 3
        val handler = ScriptedHandler { seq, attempt ->
            when {
                seq != 3 -> null
                attempt == 2 -> DataAccessResourceFailureException("커넥션 끊김")
                else -> IllegalStateException("처리기 버그 $attempt")
            }
        }
        run(stream, handler, deadLetters) { handler.handled.size == 3 && stream.pending() == 0 }
        assertThat(handler.handled).containsExactly(1, 2, 4)
        assertThat(handler.attempts.getValue(3).get()).isEqualTo(4)
        val letter = deadLetters.byKey.values.single()
        assertThat(letter.failureCount).isEqualTo(3)
        assertThat(letter.lastError).contains("처리기 버그 4")
    }
}
