package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.port.EventStreamConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import kotlin.coroutines.coroutineContext

/**
 * `worker: persister` 소비 루프(LLD platform §3.1) — 버킷 Stream `ev:{<b>}` 을 consumer group `persister` 로 읽어
 * `type` 별 [PersisterEventHandler] 에 넘기고, 처리기가 돌아온 것(= 커밋됨)만 ACK 한다.
 *
 * - 맡는 버킷은 `b mod instanceCount == instanceIndex`. 버킷 하나는 레인(코루틴) 하나만 돌아 Stream 순서대로 처리된다.
 * - 처리기가 실패하면 그 메시지에서 멈추고(뒤 메시지는 처리하지 않는다) 그 버킷은 backoff 뒤 자기 PEL 부터 다시 읽는다.
 * - 기동 직후와 [PersisterProperties.claimInterval] 마다 `XAUTOCLAIM` 으로 죽은 소비자의 pending 을 가져온다.
 */
@Component
class StreamPersister(
    private val stream: EventStreamConsumer,
    handlers: List<PersisterEventHandler> = emptyList(),
    private val properties: PersisterProperties,
    game: GameProperties,
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

        /** 내게 배달됐지만 ACK 하지 않은 메시지가 있을 수 있다(기동 직후 · 처리기 실패 뒤) — 자기 PEL 부터 읽는다. */
        var backlog = true
        var claimDue = true
        var failures = 0
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
                try {
                    if (step(state)) progressed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Redis 가 비었으면(재시작 · FLUSHALL) 그룹도 없다 — 다음 걸음에 다시 만든다(멱등)
                    state.groupReady = false
                    state.failures++
                    state.retryAtNanos = System.nanoTime() + backoffNanos(state.failures)
                    log.warn("persister.redis_failed bucket={} failures={} reason={}", state.bucket, state.failures, e.toString())
                }
            }
            if (!progressed) delay(properties.idleDelay.toMillis())
        }
    }

    /** 버킷 한 걸음 — 메시지를 읽었으면 true. */
    private suspend fun step(state: BucketState): Boolean {
        if (System.nanoTime() - state.retryAtNanos < 0) return false
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
     * [events] 를 순서대로 처리하고 처리된 것을 ACK 한다. 처리기가 실패하면 그 메시지에서 멈추고 false —
     * 버킷은 backoff 뒤 자기 PEL 부터 다시 읽는다.
     */
    private suspend fun process(state: BucketState, events: List<StreamEvent>): Boolean {
        val done = ArrayList<String>(events.size)
        var failed: StreamEvent? = null
        try {
            for (event in events) {
                if (!handle(state, event)) {
                    failed = event
                    break
                }
                done += event.id
            }
        } finally {
            // 취소돼도 이미 커밋한 것은 ACK 한다(못 하면 다시 받아 멱등 SQL 로 같은 결과)
            if (done.isNotEmpty()) withContext(NonCancellable) { stream.ack(state.bucket, done) }
        }
        if (failed == null) {
            state.failures = 0
            return true
        }
        state.failures++
        state.backlog = true
        state.retryAtNanos = System.nanoTime() + backoffNanos(state.failures)
        return false
    }

    /** 메시지 한 건 — ACK 해도 되면 true(커밋됨 · 건너뜀). 처리기가 실패하면 false. */
    private suspend fun handle(state: BucketState, event: StreamEvent): Boolean {
        val bucket = state.bucket
        if (event.deleted) {
            log.warn("persister.deleted bucket={} id={}", bucket, event.id)
            return true
        }
        val handler = handlerByType[event.type]
        if (handler == null) {
            log.warn("persister.unhandled bucket={} id={} type={}", bucket, event.id, event.type)
            return true
        }
        return try {
            handler.handle(event)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: MalformedEventException) {
            log.error("persister.malformed bucket={} id={} type={} reason={}", bucket, event.id, event.type, e.message)
            true
        } catch (e: Exception) {
            log.warn(
                "persister.handler_failed bucket={} id={} type={} failures={} reason={}",
                bucket, event.id, event.type, state.failures + 1, e.toString(),
            )
            false
        }
    }

    private fun backoffNanos(failures: Int): Long {
        val base = properties.retryBackoff.toNanos()
        val max = properties.retryBackoffMax.toNanos()
        val shift = (failures - 1).coerceIn(0, 30)
        return if (base > max shr shift) max else (base shl shift).coerceAtMost(max)
    }

    private companion object {
        val log = LoggerFactory.getLogger(StreamPersister::class.java)

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
