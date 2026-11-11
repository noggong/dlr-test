package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.domain.event.StreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.ReactiveRedisCallback
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 시험용 처리기가 쓰는 행 — 처리 순서는 `ord`. */
data class ProbeRow(val bucket: Int, val seq: Int, val inst: String)

/**
 * 시험용 처리기(ORACLE platform O15 ~ O18) — `type = probe` 메시지마다 `persister_probe` 에 한 행을 **한 트랜잭션으로** 쓴다.
 * PK `(bucket, seq)` 라 롤백되지 않은 쓰기가 있었으면 재처리가 PK 위반으로 끝없이 실패한다.
 *
 * - [blocked] 에 든 `(bucket, seq)` 는 행을 쓴 뒤 예외를 던진다(롤백 — 「커밋 전 실패」).
 * - 칸 `malformed=1` 이면 [MalformedEventException].
 */
class ProbeHandler(
    private val db: DatabaseClient,
    private val tx: TransactionalOperator,
    private val inst: String = "main",
    override val types: Set<String> = setOf(PROBE),
) : PersisterEventHandler {
    val blocked: MutableSet<Pair<Int, Int>> = ConcurrentHashMap.newKeySet()
    val attempts = ConcurrentHashMap<Pair<Int, Int>, AtomicInteger>()

    override suspend fun handle(event: StreamEvent) {
        if (event.fields["malformed"] == "1") throw MalformedEventException("시험용 형식 오류: ${event.id}")
        val bucket = event.fields.getValue("bucket").toInt()
        val seq = event.fields.getValue("seq").toInt()
        attempts.computeIfAbsent(bucket to seq) { AtomicInteger() }.incrementAndGet()
        tx.executeAndAwait {
            db.sql("INSERT INTO persister_probe (bucket, seq, msg_id, inst) VALUES (:b, :s, :id, :inst)")
                .bind("b", bucket).bind("s", seq).bind("id", event.id).bind("inst", inst)
                .fetch().rowsUpdated().awaitSingle()
            check((bucket to seq) !in blocked) { "주입한 실패: $bucket/$seq" }
        }
    }

    companion object {
        const val PROBE = "probe"
    }
}

/** 테스트 도우미 — Stream · 표를 직접 만지고 본다. */
class ProbeTable(
    private val db: DatabaseClient,
    private val redis: ReactiveStringRedisTemplate,
) {
    suspend fun reset() {
        redis.execute(ReactiveRedisCallback<String> { it.serverCommands().flushAll() }).collectList().awaitSingle()
        db.sql(
            """
            CREATE TABLE IF NOT EXISTS persister_probe (
              ord bigserial, bucket int NOT NULL, seq int NOT NULL, msg_id text NOT NULL, inst text NOT NULL,
              PRIMARY KEY (bucket, seq)
            )
            """.trimIndent(),
        ).then().awaitFirstOrNull()
        db.sql("TRUNCATE persister_probe").then().awaitFirstOrNull()
    }

    suspend fun rows(): List<ProbeRow> =
        db.sql("SELECT bucket, seq, inst FROM persister_probe ORDER BY ord")
            .map { row ->
                ProbeRow((row.get("bucket") as Number).toInt(), (row.get("seq") as Number).toInt(), row.get("inst") as String)
            }
            .all().collectList().awaitSingle()

    /** `ev:{bucket}` 에 probe 메시지를 넣고 Stream id 를 돌려준다. */
    suspend fun add(bucket: Int, seq: Int, vararg extra: Pair<String, String>, type: String = ProbeHandler.PROBE): String {
        val fields = linkedMapOf("type" to type, "bucket" to "$bucket", "seq" to "$seq") + extra
        return redis.opsForStream<String, String>().add(MapRecord.create(stream(bucket), fields)).awaitSingle().value
    }

    suspend fun pending(bucket: Int): Long =
        redis.opsForStream<String, String>().pending(stream(bucket), GROUP).awaitSingle().totalPendingMessages

    suspend fun pendingOf(bucket: Int, consumer: String): Long =
        redis.opsForStream<String, String>().pending(stream(bucket), GROUP).awaitSingle()
            .pendingMessagesPerConsumer[consumer] ?: 0L

    suspend fun length(bucket: Int): Long = redis.opsForStream<String, String>().size(stream(bucket)).awaitSingle()

    fun stream(bucket: Int): String = "ev:{$bucket}"

    companion object {
        const val GROUP = "persister"
    }
}

/** [cond] 가 참이 될 때까지 기다린다. */
suspend fun eventually(timeout: Duration = Duration.ofSeconds(20), what: String, cond: suspend () -> Boolean) {
    val end = System.nanoTime() + timeout.toNanos()
    while (!cond()) {
        check(System.nanoTime() < end) { "$timeout 안에 이루어지지 않았다: $what" }
        delay(25)
    }
}
