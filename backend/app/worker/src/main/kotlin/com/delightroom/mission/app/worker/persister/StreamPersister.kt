package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.DeadLetter
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.port.DeadLetterStore
import com.delightroom.mission.core.port.EventStreamConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * `worker: persister` 소비 루프(LLD platform §3.1) — 버킷 Stream `ev:{<b>}` 을 consumer group `persister` 로 읽어
 * `type` 별 [PersisterEventHandler] 에 넘기고, 처리기가 돌아온 것(= 커밋됨)만 ACK 한다.
 *
 * - 맡는 버킷은 `b mod instanceCount == instanceIndex`. 버킷 하나는 레인(코루틴) 하나만 돌아 Stream 순서대로 처리된다.
 * - 처리기가 실패하면 그 메시지에서 멈추고(뒤 메시지는 처리하지 않는다) 그 버킷은 backoff 뒤 자기 PEL 부터 다시 읽는다.
 * - 같은 메시지의 센 실패가 [PersisterProperties.deadLetterAfter] 번이면(형식 오류는 처음에) [DeadLetterStore] 에 쌓고 ACK 해 다음 메시지로 간다
 *   (prd D20). 일시 장애([PersisterFailures])는 세지 않고, 쌓기가 실패하면 ACK 하지 않는다.
 * - 기동 직후와 [PersisterProperties.claimInterval] 마다 `XAUTOCLAIM` 으로 죽은 소비자의 pending 을 가져온다.
 */
@Component
class StreamPersister(
    private val stream: EventStreamConsumer,
    handlers: List<PersisterEventHandler> = emptyList(),
    private val properties: PersisterProperties,
    game: GameProperties,
    private val deadLetters: DeadLetterStore,
    private val clock: Clock,
) : SmartLifecycle {
    private val handlerByType: Map<String, PersisterEventHandler> = handlerMap(handlers)

    /** 이 인스턴스가 맡는 버킷(오름차순). */
    val buckets: List<Int> =
        (0 until game.bucketCount).filter { it % properties.instanceCount == properties.instanceIndex }

    private val consumer = properties.consumerName

    @Volatile private var job: Job? = null

    override fun start() {
        if (!properties.enabled || job != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("persister"))
        val states = buckets.map(::BucketState)
        val lanes = states.withIndex().groupBy({ it.index % properties.parallelism }, { it.value })
        lanes.values.forEach { lane -> scope.launch { runLane(lane) } }
        job = scope.coroutineContext[Job]
        log.info(
            "persister.started consumer={} buckets={} lanes={} handlers={}",
            consumer, buckets.size, lanes.size, handlerByType.keys.sorted(),
        )
    }

    override fun stop() {
        val running = job ?: return
        // 처리 중이던 메시지는 ACK 되지 않아 재기동 때 자기 PEL 로 다시 처리된다
        runBlocking { running.cancelAndJoin() }
        job = null
        log.info("persister.stopped consumer={}", consumer)
    }

    override fun isRunning(): Boolean = job != null

    /** 버킷 하나의 소비 상태 — 그 버킷을 맡은 레인만 만진다. */
    private class BucketState(val bucket: Int) {
        var groupReady = false

        /** 내게 배달됐지만 ACK 하지 않은 메시지가 있을 수 있다(기동 직후 · 처리기 실패 · Redis 오류 뒤) — 자기 PEL 부터 읽는다. */
        var backlog = true
        var claimDue = true

        /** 처리기 연속 실패 수 — 메시지를 처리하면 0. */
        var failures = 0

        /** 센 실패를 세는 메시지 · 센 수 · 처음 센 시각(LLD §3.1 dead-letter 2) — 다른 메시지가 실패하면 새로 센다. */
        var strikeId: String? = null
        var strikes = 0
        var firstStrikeAt: Instant? = null

        fun clearStrikes() {
            strikeId = null
            strikes = 0
            firstStrikeAt = null
        }

        /** Redis 연속 오류 수 — 오류 없이 한 걸음을 마치면 0. */
        var redisFailures = 0
        var retryAtNanos = 0L
    }

    private suspend fun runLane(lane: List<BucketState>) {
        var nextClaimAt = System.nanoTime() + properties.claimInterval.toNanos()
        while (coroutineContext.isActive) {
            if (System.nanoTime() - nextClaimAt >= 0) {
                lane.forEach { it.claimDue = true }
                nextClaimAt = System.nanoTime() + properties.claimInterval.toNanos()
            }
            var progressed = false
            for (state in lane) {
                if (System.nanoTime() - state.retryAtNanos < 0) continue
                try {
                    if (step(state)) progressed = true
                    state.redisFailures = 0
                } catch (e: Throwable) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                    // 읽기가 Redis 에서는 됐는데 응답을 잃었거나 ACK 가 실패했을 수 있다 — 자기 PEL 부터 다시 읽어 순서를 지킨다.
                    // Redis 가 비었으면(재시작 · FLUSHALL) 그룹도 없다 — 다시 만든다(멱등). 회수도 다시 한다.
                    state.backlog = true
                    state.claimDue = true
                    state.groupReady = false
                    state.redisFailures++
                    state.retryAtNanos = System.nanoTime() + backoffNanos(state.redisFailures)
                    log.warn(
                        "persister.redis_failed bucket={} failures={} reason={}",
                        state.bucket, state.redisFailures, e.toString(),
                    )
                }
            }
            if (!progressed) delay(properties.idleDelay.toMillis())
        }
    }

    /** 버킷 한 걸음 — 메시지를 읽었으면 true. */
    private suspend fun step(state: BucketState): Boolean {
        val b = state.bucket
        if (!state.groupReady) {
            stream.ensureGroup(b)
            state.groupReady = true
        }
        if (state.claimDue) {
            state.claimDue = false
            var cursor = "0-0"
            var claimed = false
            do {
                val page = stream.claimIdle(b, consumer, properties.claimMinIdle, properties.batchSize, cursor)
                if (page.events.isNotEmpty()) claimed = true
                // 멈추면 가져온 것은 이미 내 PEL 이다 — backlog 가 다시 읽는다. 남은 쪽은 다음 회수 때 가져온다
                if (!process(state, page.events)) return true
                cursor = page.cursor
            } while (!page.done)
            if (claimed) return true
        }
        if (state.backlog) {
            val pending = stream.readOwnPending(b, consumer, properties.batchSize)
            if (pending.isNotEmpty()) {
                process(state, pending)
                return true
            }
            state.backlog = false
        }
        val fresh = stream.readNew(b, consumer, properties.batchSize)
        if (fresh.isEmpty()) return false
        process(state, fresh)
        return true
    }

    /**
     * [events] 를 순서대로 처리하고 처리된 것(dead-letter 로 쌓은 것 포함)을 ACK 한다. 처리기가 실패하고 dead-letter 로도 넘기지 못하면
     * 그 메시지에서 멈추고 false — 버킷은 backoff 뒤 자기 PEL 부터 다시 읽는다.
     */
    private suspend fun process(state: BucketState, events: List<StreamEvent>): Boolean {
        val done = ArrayList<String>(events.size)
        var failed: StreamEvent? = null
        try {
            for (event in events) {
                val outcome = handle(state, event)
                if (outcome is Failed && !(outcome.counted && deadLetter(state, event, outcome))) {
                    failed = event
                    break
                }
                if (state.strikeId == event.id) state.clearStrikes()
                done += event.id
            }
        } finally {
            if (done.isNotEmpty()) {
                if (currentCoroutineContext().isActive) {
                    stream.ack(state.bucket, done) // 실패하면 Redis 오류 — 레인이 backlog 로 다시 읽는다
                } else {
                    // 멈추는 중에도 이미 커밋한 것은 ACK 하되 오래 막히지 않게(못 하면 다시 받아 멱등 SQL 로 같은 결과)
                    withContext(NonCancellable) { withTimeoutOrNull(ACK_ON_STOP_MS) { stream.ack(state.bucket, done) } }
                }
            }
        }
        if (failed == null) {
            state.failures = 0
            return true
        }
        state.failures++
        state.backlog = true
        state.retryAtNanos = System.nanoTime() + backoffNanos(state.failures)
        if (state.failures >= STUCK_AFTER) {
            // 멈춘 버킷은 트림도 멈춰 Stream 이 자란다 — 운영 경보 대상
            log.error("persister.stuck bucket={} id={} type={} failures={}", state.bucket, failed.id, failed.type, state.failures)
        }
        return false
    }

    /** 메시지 한 건의 결과 — [Done] 이면 ACK 해도 된다(커밋됨 · 건너뜀). */
    private sealed interface Outcome

    private data object Done : Outcome

    /** [counted] 면 dead-letter 횟수에 든다. [final] 이면 횟수와 상관없이 바로 dead-letter(다시 해도 같은 형식 오류). */
    private data class Failed(val counted: Boolean, val reason: String, val final: Boolean = false) : Outcome

    private suspend fun handle(state: BucketState, event: StreamEvent): Outcome {
        val bucket = state.bucket
        if (event.deleted) {
            log.warn("persister.deleted bucket={} id={}", bucket, event.id)
            return Done
        }
        val handler = handlerByType[event.type]
        if (handler == null) {
            // 처리기가 붙기 전 이벤트를 그냥 ACK · 트림으로 잃지 않는다 — 센 실패로 dead-letter 까지 간다(LLD §3.1 처리기 계약)
            log.warn("persister.unhandled bucket={} id={} type={} failures={}", bucket, event.id, event.type, state.failures + 1)
            return Failed(counted = true, reason = "unhandled type: ${event.type}")
        }
        return try {
            handler.handle(event)
            Done
        } catch (e: MalformedEventException) {
            log.error("persister.malformed bucket={} id={} type={} reason={}", bucket, event.id, event.type, e.message)
            Failed(counted = true, reason = e.toString(), final = true)
        } catch (e: Throwable) {
            // 루프가 멈출 때의 취소만 취소다 — 처리기 안의 타임아웃 · Error(TODO() 등)는 처리기 실패로 레인을 살린다
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            val counted = PersisterFailures.counts(e)
            log.warn(
                "persister.handler_failed bucket={} id={} type={} failures={} counted={} reason={}",
                bucket, event.id, event.type, state.failures + 1, counted, e.toString(),
            )
            Failed(counted = counted, reason = e.toString())
        }
    }

    /**
     * 센 실패 하나를 더하고, 문턱에 닿았으면 dead-letter 에 쌓는다(LLD §3.1 dead-letter 2 ~ 4). 쌓았으면 true — 그 메시지를 ACK 한다.
     * 쌓기가 실패하면(DB 장애) false — ACK 하지 않고 다음 시도에서 다시 쌓는다.
     */
    private suspend fun deadLetter(state: BucketState, event: StreamEvent, failure: Failed): Boolean {
        val now = clock.instant()
        if (state.strikeId != event.id) {
            state.strikeId = event.id
            state.strikes = 0
            state.firstStrikeAt = now
        }
        state.strikes++
        if (!failure.final && state.strikes < properties.deadLetterAfter) return false
        val letter = DeadLetter(
            bucket = state.bucket,
            streamId = event.id,
            type = event.type,
            fields = event.fields,
            failureCount = state.strikes,
            lastError = failure.reason,
            firstFailedAt = state.firstStrikeAt ?: now,
            lastFailedAt = now,
        )
        try {
            deadLetters.put(letter)
        } catch (e: Throwable) {
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            log.warn(
                "persister.dead_letter_failed bucket={} id={} type={} failures={} reason={}",
                state.bucket, event.id, event.type, state.strikes, e.toString(),
            )
            return false
        }
        log.error(
            "persister.dead_lettered bucket={} id={} type={} failures={} reason={}",
            state.bucket, event.id, event.type, state.strikes, failure.reason,
        )
        state.clearStrikes()
        // 쌓은 메시지는 처리된 것 — 같은 배치의 다음 메시지가 실패해도 backoff 를 처음부터 센다
        state.failures = 0
        return true
    }

    private fun backoffNanos(failures: Int): Long {
        val base = properties.retryBackoff.toNanos()
        val max = properties.retryBackoffMax.toNanos()
        val shift = (failures - 1).coerceIn(0, 30)
        return if (base > max shr shift) max else (base shl shift).coerceAtMost(max)
    }

    private companion object {
        val log = LoggerFactory.getLogger(StreamPersister::class.java)

        /** 연속 실패가 이만큼이면 ERROR `persister.stuck`. */
        const val STUCK_AFTER = 10

        /** 멈추는 중의 ACK 제한. */
        const val ACK_ON_STOP_MS = 5_000L

        fun handlerMap(handlers: List<PersisterEventHandler>): Map<String, PersisterEventHandler> {
            val byType = HashMap<String, PersisterEventHandler>()
            for (handler in handlers) {
                for (type in handler.types) {
                    val other = byType.putIfAbsent(type, handler)
                    check(other == null) {
                        "persister 처리기가 같은 type 을 둘 맡는다: $type — ${other!!.javaClass.name} · ${handler.javaClass.name}"
                    }
                }
            }
            return byType
        }
    }
}
