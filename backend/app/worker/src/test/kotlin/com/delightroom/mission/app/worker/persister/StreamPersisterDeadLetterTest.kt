package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.DeadLetter
import com.delightroom.mission.core.port.DeadLetterStore
import com.delightroom.mission.core.port.EventStreamConsumer
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** `persister_dead_letters` 한 행(시험에서 보는 칸). */
private data class LetterRow(
    val bucket: Int,
    val streamId: String,
    val type: String?,
    val failureCount: Int,
    val lastError: String,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
)

/**
 * persister dead-letter(ORACLE platform O19 · O20 ⓐ · prd D20 · LLD platform §3.1 dead-letter) — 실제 PostgreSQL `persister_dead_letters` 와
 * Redis Stream. 스키마는 `spring.sql.init` 이 적용한다. 스프링이 띄운 루프는 끄고 [StreamPersister] 를 직접 만든다.
 */
@SpringBootTest(
    properties = [
        "game.bucket-count=8",
        "spring.sql.init.mode=always",
        "worker.persister.enabled=false",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class StreamPersisterDeadLetterTest
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
        fun reset(): Unit = runBlocking {
            probe.reset()
            db.sql("TRUNCATE persister_dead_letters").then().awaitFirstOrNull()
        }

        private fun persister(handler: ProbeHandler) =
            StreamPersister(
                stream,
                listOf(handler),
                PersisterProperties(
                    parallelism = 2,
                    idleDelay = Duration.ofMillis(20),
                    retryBackoff = Duration.ofMillis(100),
                    retryBackoffMax = Duration.ofMillis(200),
                    deadLetterAfter = 3,
                ),
                game,
                deadLetters,
                clock,
            )

        @Test
        fun `O19 3번 실패한 메시지 · 처리기 없는 type · 형식 오류는 dead-letter 1행씩 쌓이고 ACK 뒤 다음 메시지를 처리한다`(): Unit = runBlocking {
            val handler = ProbeHandler(db, tx).apply { blocked += 0 to 2 }
            val ids = linkedMapOf(
                1 to probe.add(0, 1),
                2 to probe.add(0, 2), // 늘 실패(행을 쓴 뒤 예외 — 롤백)
                3 to probe.add(0, 3),
                4 to probe.add(0, 4, type = ORPHAN), // 처리기 없음
                5 to probe.add(0, 5),
                6 to probe.add(0, 6, "malformed" to "1"), // 형식 오류
                7 to probe.add(0, 7),
            )
            val persister = persister(handler)
            persister.start()
            try {
                eventually(what = "정상 4행") { probe.rows().count { it.bucket == 0 } == 4 }
                eventually(what = "pending 0 · 트림") { probe.pending(0) == 0L && probe.length(0) <= 1 }
            } finally {
                persister.stop()
            }

            assertThat(probe.rows().map { it.bucket to it.seq }).containsExactly(0 to 1, 0 to 3, 0 to 5, 0 to 7)
            assertThat(handler.attempts.getValue(0 to 2).get()).isEqualTo(3)

            val letters = letters().associateBy { it.streamId }
            assertThat(letters.keys).containsExactlyInAnyOrder(ids[2], ids[4], ids[6])
            assertThat(letters.values).allMatch { it.bucket == 0 && !it.firstFailedAt.isAfter(it.lastFailedAt) }

            val failing = letters.getValue(ids.getValue(2))
            assertThat(failing.type).isEqualTo(ProbeHandler.PROBE)
            assertThat(failing.failureCount).isEqualTo(3)
            assertThat(failing.lastError).contains("주입한 실패")
            assertThat(fields(ids.getValue(2))).isEqualTo(mapOf("type" to ProbeHandler.PROBE, "bucket" to "0", "seq" to "2"))

            val orphan = letters.getValue(ids.getValue(4))
            assertThat(orphan.type).isEqualTo(ORPHAN)
            assertThat(orphan.failureCount).isEqualTo(3)
            assertThat(orphan.lastError).contains("unhandled type")
            assertThat(fields(ids.getValue(4))).isEqualTo(mapOf("type" to ORPHAN, "bucket" to "0", "seq" to "4"))

            val malformed = letters.getValue(ids.getValue(6))
            assertThat(malformed.failureCount).isEqualTo(1)
            assertThat(malformed.lastError).contains("MalformedEventException")
            assertThat(fields(ids.getValue(6))).containsEntry("malformed", "1").containsEntry("seq", "6")
        }

        @Test
        fun `O20 같은 메시지를 다시 쌓아도 한 행이고 실패 수 · 시각만 넓힌다`(): Unit = runBlocking {
            val t0 = Instant.parse("2026-11-11T00:00:00Z")
            val fields = mapOf("type" to "probe", "seq" to "1")
            deadLetters.put(DeadLetter(5, "100-0", "probe", fields, 3, "첫째", t0, t0.plusSeconds(3)))
            deadLetters.put(DeadLetter(5, "100-0", "probe", fields, 4, "둘째", t0.plusSeconds(10), t0.plusSeconds(13)))
            deadLetters.put(DeadLetter(5, "100-0", "probe", fields, 2, "셋째", t0.minusSeconds(5), t0.plusSeconds(1)))
            // 다른 버킷의 같은 id 는 다른 메시지 · type 칸이 없던 메시지는 NULL
            deadLetters.put(DeadLetter(6, "100-0", null, mapOf("seq" to "9"), 3, "넷째", t0, t0))

            val rows = letters().associateBy { it.bucket }
            assertThat(rows.keys).containsExactlyInAnyOrder(5, 6)
            assertThat(rows.getValue(5)).isEqualTo(
                LetterRow(5, "100-0", "probe", 4, "셋째", t0.minusSeconds(5), t0.plusSeconds(13)),
            )
            assertThat(fields("100-0", bucket = 5)).isEqualTo(fields)
            assertThat(rows.getValue(6).type).isNull()
            assertThat(fields("100-0", bucket = 6)).isEqualTo(mapOf("seq" to "9"))
        }

        private suspend fun letters(): List<LetterRow> =
            db.sql(
                "SELECT bucket, stream_id, event_type, failure_count, last_error, first_failed_at, last_failed_at " +
                    "FROM persister_dead_letters ORDER BY bucket, stream_id",
            )
                .map { row ->
                    LetterRow(
                        bucket = (row.get("bucket") as Number).toInt(),
                        streamId = row.get("stream_id", String::class.java)!!,
                        type = row.get("event_type", String::class.java),
                        failureCount = (row.get("failure_count") as Number).toInt(),
                        lastError = row.get("last_error", String::class.java)!!,
                        firstFailedAt = row.get("first_failed_at", OffsetDateTime::class.java)!!.toInstant(),
                        lastFailedAt = row.get("last_failed_at", OffsetDateTime::class.java)!!.toInstant(),
                    )
                }
                .all().collectList().awaitSingle()

        /** `fields` JSON 객체를 이름 → 값으로. */
        private suspend fun fields(streamId: String, bucket: Int = 0): Map<String, String> =
            db.sql(
                "SELECT f.key, f.value FROM persister_dead_letters d, jsonb_each_text(d.fields) f " +
                    "WHERE d.bucket = :b AND d.stream_id = :id",
            )
                .bind("b", bucket).bind("id", streamId)
                .map { row -> row.get("key", String::class.java)!! to row.get("value", String::class.java)!! }
                .all().collectList().awaitSingle().toMap()

        private companion object {
            /** 처리기가 없는 type. */
            const val ORPHAN = "orphan.type"
        }
    }
