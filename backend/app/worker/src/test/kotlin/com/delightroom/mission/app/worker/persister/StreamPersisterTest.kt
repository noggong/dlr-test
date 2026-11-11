package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.port.DeadLetterStore
import com.delightroom.mission.core.port.EventStreamConsumer
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock
import java.time.Duration

/**
 * persister 소비 루프(ORACLE platform O15 ~ O18 · LLD platform §3.1). 스프링이 띄운 루프는 끄고(`enabled=false`)
 * 시험마다 설정을 바꿔 [StreamPersister] 를 직접 만든다 — 포트(`EventStreamConsumer`) · DB 는 스프링 빈 그대로.
 */
@SpringBootTest(properties = ["worker.persister.enabled=false", "worker.member.enabled=false", "game.bucket-count=8"])
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class StreamPersisterTest
    @Autowired
    constructor(
        private val stream: EventStreamConsumer,
        private val redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
        private val tx: TransactionalOperator,
        private val game: GameProperties,
        private val deadLetters: DeadLetterStore,
        private val clock: Clock,
    ) {
        private val probe = ProbeTable(db, redis)

        @BeforeEach
        fun reset(): Unit = runBlocking { probe.reset() }

        private fun properties(
            index: Int = 0,
            count: Int = 1,
            claimMinIdle: Duration = Duration.ofSeconds(30),
            deadLetterAfter: Int = 3,
        ) = PersisterProperties(
            instanceIndex = index,
            instanceCount = count,
            parallelism = 4,
            batchSize = 100,
            idleDelay = Duration.ofMillis(20),
            claimInterval = Duration.ofSeconds(30),
            claimMinIdle = claimMinIdle,
            retryBackoff = Duration.ofMillis(100),
            retryBackoffMax = Duration.ofMillis(200),
            deadLetterAfter = deadLetterAfter,
        )

        private inline fun <T> running(vararg persisters: StreamPersister, block: () -> T): T {
            persisters.forEach { it.start() }
            try {
                return block()
            } finally {
                persisters.forEach { it.stop() }
            }
        }

        private fun persister(handler: ProbeHandler, props: PersisterProperties = properties()) =
            StreamPersister(stream, listOf(handler), props, game, deadLetters, clock)

        @Test
        fun `O15 버킷마다 Stream 순서대로 한 번씩 처리하고 ACK · 트림한다`(): Unit = runBlocking {
            // 그룹이 생기기 전 메시지 — 버킷 0 의 앞 10개 · 버킷 1 · 2 전부
            (1..10).forEach { probe.add(0, it) }
            for (b in 1..2) (1..30).forEach { probe.add(b, it) }
            val handler = ProbeHandler(db, tx)
            running(persister(handler)) {
                (11..30).forEach { probe.add(0, it) }
                eventually(what = "90행") { probe.rows().size == 90 }
                eventually(what = "pending 0 · 트림") {
                    (0..2).all { probe.pending(it) == 0L && probe.length(it) <= 1 }
                }
            }
            val rows = probe.rows()
            assertThat(rows.map { it.bucket to it.seq }).doesNotHaveDuplicates()
            for (b in 0..2) {
                assertThat(rows.filter { it.bucket == b }.map { it.seq }).containsExactlyElementsOf(1..30)
            }
            assertThat(handler.attempts.values.map { it.get() }).allMatch { it == 1 }
        }

        @Test
        fun `O16 처리기가 실패하면 그 메시지에서 멈추고 롤백 뒤 같은 메시지부터 순서대로 다시 처리한다`(): Unit = runBlocking {
            // 문턱을 크게 두어 dead-letter 로 가지 않게 한다 — 문턱 뒤는 O19(StreamPersisterDeadLetterTest)
            val noDeadLetter = properties(deadLetterAfter = 1_000)
            val handler = ProbeHandler(db, tx).apply { blocked += 1 to 3 }
            (1..8).forEach { probe.add(1, it) }
            (1..5).forEach { probe.add(2, it) }
            probe.add(4, 1)
            probe.add(4, 2, type = LATE)
            probe.add(4, 3)
            running(persister(handler, noDeadLetter)) {
                // 다른 버킷은 막히지 않는다
                eventually(what = "버킷 2 처리") { probe.rows().count { it.bucket == 2 } == 5 }
                eventually(what = "버킷 2 pending 0") { probe.pending(2) == 0L }
                // 처리기 없는 type 은 ACK 되지 않고 그 버킷의 뒤 메시지도 처리되지 않는다
                eventually(what = "버킷 4 첫 행") { probe.rows().count { it.bucket == 4 } == 1 }
                // 실패가 이어지는 동안: 3번은 여러 번 시도됐지만 행 없음 · 뒤 메시지 행 0 · ACK 안 됨
                eventually(what = "재시도") { (handler.attempts[1 to 3]?.get() ?: 0) >= 3 }
                assertThat(probe.rows().filter { it.bucket == 1 }.map { it.seq }).containsExactly(1, 2)
                assertThat(handler.attempts.keys.filter { it.first == 1 }.map { it.second }).containsExactlyInAnyOrder(1, 2, 3)
                assertThat(probe.pending(1)).isEqualTo(6)
                assertThat(probe.length(1)).isGreaterThanOrEqualTo(6) // 트림이 pending 을 지우지 않는다
                assertThat(probe.rows().filter { it.bucket == 4 }.map { it.seq }).containsExactly(1)
                assertThat(probe.pending(4)).isEqualTo(2)
                assertThat(probe.length(4)).isGreaterThanOrEqualTo(2)

                handler.blocked.clear()
                eventually(what = "버킷 1 전부") { probe.rows().count { it.bucket == 1 } == 8 }
                eventually(what = "버킷 1 pending 0") { probe.pending(1) == 0L }
            }
            val rows = probe.rows()
            assertThat(rows.filter { it.bucket == 1 }.map { it.seq }).containsExactlyElementsOf(1..8)
            assertThat(rows.map { it.bucket to it.seq }).doesNotHaveDuplicates()
            // 3번 뒤 메시지는 3번이 성공한 뒤에야 한 번만 시도됐다
            assertThat((4..8).map { handler.attempts[1 to it]?.get() }).containsOnly(1)

            // 처리기를 붙여 다시 띄우면 멈췄던 메시지부터 순서대로 처리된다
            running(persister(ProbeHandler(db, tx, types = setOf(ProbeHandler.PROBE, LATE)), noDeadLetter)) {
                eventually(what = "버킷 4 전부") { probe.rows().count { it.bucket == 4 } == 3 && probe.pending(4) == 0L }
            }
            assertThat(probe.rows().filter { it.bucket == 4 }.map { it.seq }).containsExactly(1, 2, 3)
        }

        @Test
        fun `O17 기동 때 자기 PEL 과 죽은 소비자의 pending 을 회수한다`(): Unit = runBlocking {
            stream.ensureGroup(0)
            stream.ensureGroup(1)
            (1..3).forEach { probe.add(0, it) }
            (1..2).forEach { probe.add(1, it) }
            readAsConsumer(0, "worker-0") // ① 내 이름으로 읽고 ACK 안 함(재기동 전 죽음)
            readAsConsumer(1, "worker-9") // ② 다른(죽은) 소비자가 읽고 ACK 안 함
            assertThat(probe.pending(0)).isEqualTo(3)
            assertThat(probe.pendingOf(1, "worker-9")).isEqualTo(2)

            // ① 은 회수 idle 과 상관없이 자기 PEL 로 읽는다 — idle 기준을 1시간으로 두어 ② 는 아직 회수되지 않는다
            running(persister(ProbeHandler(db, tx), properties(claimMinIdle = Duration.ofHours(1)))) {
                eventually(what = "자기 PEL 3행") { probe.rows().count { it.bucket == 0 } == 3 }
                eventually(what = "버킷 0 pending 0") { probe.pending(0) == 0L }
                delay(300)
                assertThat(probe.rows().count { it.bucket == 1 }).isZero()
                assertThat(probe.pendingOf(1, "worker-9")).isEqualTo(2)
            }

            delay(1_100) // ② 의 idle 이 1초를 넘게
            running(persister(ProbeHandler(db, tx), properties(claimMinIdle = Duration.ofSeconds(1)))) {
                eventually(what = "회수 2행") { probe.rows().count { it.bucket == 1 } == 2 }
                eventually(what = "버킷 1 pending 0") { probe.pending(1) == 0L }
            }
            assertThat(probe.pendingOf(1, "worker-9")).isZero()
            assertThat(probe.rows().map { it.bucket to it.seq })
                .containsExactlyInAnyOrder(0 to 1, 0 to 2, 0 to 3, 1 to 1, 1 to 2)
        }

        @Test
        fun `O18 인스턴스 둘이 버킷을 나눠 맡고 같은 type 처리기가 둘이면 기동 실패`(): Unit = runBlocking {
            for (b in 0 until 8) (1..4).forEach { probe.add(b, it) }
            val even = persister(ProbeHandler(db, tx, inst = "i0"), properties(index = 0, count = 2))
            val odd = persister(ProbeHandler(db, tx, inst = "i1"), properties(index = 1, count = 2))
            assertThat(even.buckets).containsExactly(0, 2, 4, 6)
            assertThat(odd.buckets).containsExactly(1, 3, 5, 7)
            running(even, odd) {
                eventually(what = "32행") { probe.rows().size == 32 }
                eventually(what = "pending 0") { (0 until 8).all { probe.pending(it) == 0L } }
            }
            val rows = probe.rows()
            assertThat(rows.map { it.bucket to it.seq }).doesNotHaveDuplicates()
            assertThat(rows).allMatch { it.inst == if (it.bucket % 2 == 0) "i0" else "i1" }

            assertThatThrownBy { StreamPersister(stream, listOf(ProbeHandler(db, tx), ProbeHandler(db, tx)), properties(), game, deadLetters, clock) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("probe")
        }

        private companion object {
            /** 처음엔 처리기가 없다가 나중에 붙는 type. */
            const val LATE = "late.type"
        }

        private suspend fun readAsConsumer(bucket: Int, consumer: String) {
            redis.opsForStream<String, String>()
                .read(
                    Consumer.from(ProbeTable.GROUP, consumer),
                    StreamReadOptions.empty().count(100),
                    StreamOffset.create(probe.stream(bucket), ReadOffset.lastConsumed()),
                ).collectList().awaitSingle()
        }
    }
