package com.delightroom.mission.core.service

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.domain.result.DeadlineReport
import com.delightroom.mission.core.domain.result.FailedGroup
import com.delightroom.mission.core.port.GroupDayRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * `worker: deadline` 한 단계(LLD deadline-result §3.2) — 맡은 버킷의 그날 그룹마다 Lua `deadline` 을 한 번씩 부른다.
 *
 * - 맡는 버킷은 `b mod instanceCount == instanceIndex`. 버킷은 코루틴 [Options.bucketParallelism] 개가 하나씩 꺼내 돈다.
 * - 그룹 호출은 단계 전체에서 동시에 [Options.inflight] 개까지 — 한 연결에 겹쳐 보내 파이프라인이 된다.
 * - Lua 가 멱등이라 같은 그룹을 두 번 불러도(인스턴스 겹침 · 재기동 · 1차와 2차 겹침) 결과는 같다.
 *
 * worker 만 쓰므로 빈 주석을 달지 않는다 — worker 의 설정이 만든다. 로그는 돌려준 [DeadlineReport] 로 부른 쪽이 남긴다.
 */
class DeadlineService(
    private val groups: GroupDayRepository,
    game: GameProperties,
    private val options: Options = Options(),
) {
    /** LLD deadline-result §3.2 의 수치(설정 `worker.deadline.*`). */
    data class Options(
        val instanceIndex: Int = 0,
        val instanceCount: Int = 1,
        val bucketParallelism: Int = 32,
        val inflight: Int = 256,
        /** 오류(연결 · 타임아웃 · 스크립트 오류) 난 그룹 · 버킷을 다시 하는 횟수. */
        val retry: Int = 3,
        /** 첫 재시도 전 대기 — 두 배씩 는다. */
        val retryBackoff: Duration = Duration.ofMillis(100),
        /** `EARLY`(워커 시계가 Redis 보다 빠름) 뒤 그 그룹을 다시 부르기까지. */
        val earlyRetryDelay: Duration = Duration.ofMillis(20),
        val earlyRetryMax: Int = 200,
    ) {
        init {
            require(instanceCount >= 1) { "worker.deadline.instance-count 는 1 이상이다: $instanceCount" }
            require(instanceIndex in 0 until instanceCount) {
                "worker.deadline.instance-index 는 0 ~ ${instanceCount - 1} 이다: $instanceIndex"
            }
            require(bucketParallelism >= 1) { "worker.deadline.bucket-parallelism 은 1 이상이다: $bucketParallelism" }
            require(inflight >= 1) { "worker.deadline.inflight 는 1 이상이다: $inflight" }
            require(retry >= 0) { "worker.deadline.retry 는 0 이상이다: $retry" }
            require(!retryBackoff.isNegative && !earlyRetryDelay.isNegative) { "worker.deadline 의 대기 시간은 0 이상이다" }
            require(earlyRetryMax >= 0) { "worker.deadline.early-retry-max 는 0 이상이다: $earlyRetryMax" }
        }
    }

    /** 이 인스턴스가 맡는 버킷(오름차순). */
    val buckets: List<Int> = (0 until game.bucketCount).filter { it % options.instanceCount == options.instanceIndex }

    /** [date] 게임의 [phase] 를 맡은 버킷 전부에 돈다 — 모든 버킷이 끝나야 돌아온다. */
    suspend fun runPhase(date: LocalDate, phase: DeadlinePhase): DeadlineReport {
        val started = TimeSource.Monotonic.markNow()
        val run = PhaseRun(date, phase)
        coroutineScope {
            val queue = Channel<Int>(Channel.UNLIMITED)
            buckets.forEach { queue.send(it) }
            queue.close()
            repeat(minOf(options.bucketParallelism, buckets.size)) {
                launch { for (bucket in queue) run.bucket(bucket) }
            }
        }
        val codes = DeadlineCode.entries
        return DeadlineReport(
            date = date,
            phase = phase,
            buckets = buckets.size,
            counts = codes.indices.filter { run.counts[it] > 0 }.associate { codes[it] to run.counts[it] },
            failedGroups = run.failedGroups.toList(),
            failedBuckets = run.failedBuckets.sorted(),
            elapsed = started.elapsedNow().toJavaDuration(),
        )
    }

    /** 한 단계 실행의 공유 상태 — 버킷 코루틴들이 함께 쓴다. */
    private inner class PhaseRun(val date: LocalDate, val phase: DeadlinePhase) {
        val inflight = Semaphore(options.inflight)
        val counts = AtomicIntegerArray(DeadlineCode.entries.size)
        val failedGroups = ConcurrentLinkedQueue<FailedGroup>()
        val failedBuckets = ConcurrentLinkedQueue<Int>()

        /** 버킷 하나 — `SSCAN` 이 오류면 처음부터 다시 돈다(이미 시도한 gid 는 건너뛴다). */
        suspend fun bucket(bucket: Int) {
            val seen = ConcurrentHashMap.newKeySet<GroupId>()
            val errors = ConcurrentHashMap<GroupId, Throwable>()
            // 그룹 호출은 바깥 범위에 띄운다 — SSCAN 이 도중에 실패해도 이미 띄운 호출은 끝까지 간다
            coroutineScope {
                var rescans = 0
                while (true) {
                    try {
                        groups.scanDayGroups(bucket, date).collect { gid ->
                            if (!seen.add(gid)) return@collect
                            inflight.acquire()
                            launch {
                                try {
                                    attempt(gid)?.let { errors[gid] = it }
                                } finally {
                                    inflight.release()
                                }
                            }
                        }
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (rescans == options.retry) {
                            failedBuckets += bucket
                            break
                        }
                        delay(backoffMillis(rescans++))
                    }
                }
            }
            retryErrors(errors)
        }

        /** 오류 난 그룹을 버킷이 끝난 뒤 지수 백오프로 다시 — 그래도 실패면 [failedGroups]. */
        private suspend fun retryErrors(errors: MutableMap<GroupId, Throwable>) {
            for (round in 0 until options.retry) {
                if (errors.isEmpty()) return
                delay(backoffMillis(round))
                for (gid in errors.keys.toList()) {
                    inflight.acquire()
                    val error = try {
                        attempt(gid)
                    } finally {
                        inflight.release()
                    }
                    if (error == null) errors.remove(gid) else errors[gid] = error
                }
            }
            errors.forEach { (gid, e) -> failedGroups += FailedGroup(gid, e.toString()) }
        }

        /** 그룹 하나를 처리해 셈한다 — 오류면 그 오류를 돌려준다(셈하지 않음). `EARLY` 는 여기서 기다려 다시 부른다. */
        suspend fun attempt(gid: GroupId): Throwable? {
            var early = 0
            while (true) {
                val outcome = try {
                    groups.runDeadline(gid, date, phase)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return e
                }
                if (outcome.code != DeadlineCode.EARLY) {
                    counts.incrementAndGet(outcome.code.ordinal)
                    return null
                }
                if (early++ == options.earlyRetryMax) {
                    failedGroups += FailedGroup(gid, DeadlineCode.EARLY.name)
                    return null
                }
                delay(options.earlyRetryDelay.toMillis())
            }
        }

        private fun backoffMillis(round: Int): Long = options.retryBackoff.toMillis() shl minOf(round, 20)
    }
}
