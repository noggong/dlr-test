package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.DemoClockProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.DemoClockRules
import com.delightroom.mission.core.port.DemoClockStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 로컬 데모 시계(prd D25 · LLD platform §4.4). `game.demo.enabled=true` 일 때만 [com.delightroom.mission.core.config.CoreConfiguration] 이 만든다.
 *
 * - [start]: 첫 이동량을 `SET NX` 로 두고(먼저 쓴 앱의 값이 정본) 읽어 [GameProperties.shift] 에 반영한 뒤, `sync-interval` 마다 다시 읽는다.
 *   빈을 만들 때 부르므로 잡의 `SmartLifecycle.start` 보다 먼저 시각이 맞는다.
 * - [move]: 앞으로만 · `max-span` 안(P12). 사이의 경계마다 멈춰 `worker` 의 완료 표시를 기다린다(prd D26). `compareAndSet` 으로 써서 동시 이동이 서로를 덮지 않는다.
 * - [markDone]: `worker` 잡이 경계의 일을 끝냈다는 표시(P16 — [DemoJobAck] 로 이어진다).
 */
class DemoClockService(
    private val store: DemoClockStore,
    private val game: GameProperties,
    private val demo: DemoClockProperties,
    private val clock: Clock,
) {
    /** 시각 이동 대상 — 프리셋 또는 절대 게임 시각. */
    sealed interface Target {
        data class Preset(val preset: DemoClockRules.Preset) : Target

        data class At(val at: Instant) : Target
    }

    /** 지금 시계 — 응답(`DevClockResponse`)의 재료. */
    data class Snapshot(
        val gameNow: Instant,
        val realNow: Instant,
        val shift: Duration,
        val moveLimit: Instant,
    )

    /** 한 앱 안의 이동은 하나씩(앱 사이는 `compareAndSet` 과 다시 재기 — LLD §4.4-3). */
    private val moving = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("demo-clock"))

    fun start() {
        runBlocking { initialize() }
        scope.launch { syncLoop() }
    }

    fun close() {
        scope.cancel()
    }

    /** 첫 이동량을 정하고(키가 없을 때만) 정본 값을 반영한다. */
    suspend fun initialize() {
        val candidate = micros(DemoClockRules.first(clock.instant(), demo.lead, game))
        val wrote = store.setIfAbsent(candidate)
        val value = store.get() ?: candidate
        apply(value)
        val now = game.now(clock)
        log.log(
            System.Logger.Level.INFO,
            "demo-clock.ready wrote=$wrote shiftUs=$value gameNow=$now nextStart=${DemoClockRules.preset(now, DemoClockRules.Preset.START, game).plus(DemoClockRules.PRESET_LEAD)}",
        )
    }

    /**
     * 한 번 다시 읽기 — 값이 **커졌을 때만** 반영한다(앞으로만). 정본이 이 앱 값보다 작거나(Redis 를 잃고 다른 앱이 새 후보를 심음) 없으면
     * 이 앱 값을 다시 심는다 — 게임 시각이 뒤로 가지 않는다. `move` 와 겹쳐 옛 값을 읽어도 작은 값이라 반영하지 않는다.
     */
    suspend fun syncOnce() {
        stored()
    }

    /** 정본 값 — 없거나 이 앱 값보다 작으면 이 앱 값을 다시 심고, 크면 반영한다. 끝나면 정본 = 이 앱 값. */
    private suspend fun stored(): Long {
        repeat(MOVE_ATTEMPTS) {
            val local = game.shift.micros
            val value = store.get()
            when {
                value == null -> if (store.setIfAbsent(local)) {
                    log.log(System.Logger.Level.WARNING, "demo-clock.reseeded shiftUs=$local — 키가 없어 이 앱 값을 다시 심었다")
                    return local
                }
                value > local -> { apply(value); return value }
                value == local -> return value
                else -> if (store.compareAndSet(value, local)) {
                    log.log(System.Logger.Level.ERROR, "demo-clock.rejected_backward storedUs=$value localUs=$local — 뒤로 가는 값을 덮었다")
                    return local
                }
            }
        }
        return game.shift.micros
    }

    private suspend fun syncLoop() {
        while (true) {
            delay(demo.syncInterval.toMillis())
            try {
                syncOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "demo-clock.sync_failed — 다음 주기에 다시 읽는다", e)
            }
        }
    }

    /**
     * 게임 시각을 [target] 으로 옮긴다(P12 · LLD §4.4-3 · prd D26). 검사(앞으로 · `max-span` 안)에 걸리면 `409 CLOCK_MOVE_REJECTED`.
     * 사이의 경계마다 시각을 그 경계로 옮기고([step]) `worker` 가 그 일을 끝냈다는 표시를 본 뒤([awaitDone]) 다음으로 간다.
     * 표시가 `job-wait` 안에 오지 않으면 그 경계에 멈춘 채 409(P17). 끝나면(멈췄어도) 실제로 옮긴 만큼 세션 키 TTL 을 줄인다(P15).
     */
    suspend fun move(target: Target): Snapshot = moving.withLock {
        stored()
        val now = game.now(clock)
        val at = when (target) {
            is Target.Preset -> DemoClockRules.preset(now, target.preset, game)
            is Target.At -> target.at
        }
        val check = DemoClockRules.check(now, at, demo.maxSpan)
        if (!check.ok) throw rejected(check.reason)
        val from = game.shift.micros
        try {
            for (b in DemoClockRules.boundaries(now, at, game)) {
                step(b.at)
                awaitDone(b)
            }
            step(at)
        } finally {
            val moved = game.shift.micros - from
            if (moved > 0) {
                val shortened = store.shortenSessions(Duration.of(moved, ChronoUnit.MICROS))
                log.log(System.Logger.Level.INFO, "demo-clock.moved to=$at now=${game.now(clock)} sessionsShortened=$shortened")
            }
        }
        snapshot()
    }

    /** 게임 시각을 [at] 으로 — 이미 지났으면(다른 이동 · 실제 시간) 쓰지 않는다. 다른 이동과 겹쳐 지면 한 번 더 잰다. */
    private suspend fun step(at: Instant) {
        repeat(MOVE_ATTEMPTS) {
            val seen = stored()
            val now = game.now(clock)
            if (now >= at) return
            val next = Math.addExact(seen, micros(Duration.between(now, at)))
            if (store.compareAndSet(seen, next)) {
                apply(next)
                return
            }
        }
        throw rejected("다른 이동과 겹쳤다 — 다시 보내 주세요(지금 ${game.now(clock)})")
    }

    /** [b] 의 일이 끝났다는 표시를 `job-wait` 까지 기다린다(P16 · P17). */
    private suspend fun awaitDone(b: DemoClockRules.Boundary) {
        val until = System.nanoTime() + demo.jobWait.toNanos()
        while (true) {
            if (DemoClockRules.isDone(store.doneDates(), b)) {
                log.log(System.Logger.Level.INFO, "demo-clock.boundary_done job=${b.job.key} date=${b.date} gameNow=${game.now(clock)}")
                return
            }
            if (System.nanoTime() >= until) {
                throw rejected(
                    "worker 가 ${b.job.key} ${b.date} 를 ${demo.jobWait.seconds}초 안에 끝내지 않았다 — " +
                        "게임 시각은 ${game.now(clock)} 에 멈췄다(다시 보내면 이어 간다)",
                )
            }
            delay(JOB_POLL_MS)
        }
    }

    /** 경계의 일이 [date] 까지 끝났다는 표시를 쓴다(P16 — `worker` 잡이 부른다). 실패는 로그만 — 잡을 막지 않는다. */
    suspend fun markDone(field: String, date: LocalDate) {
        try {
            if (store.markDone(field, date)) log.log(System.Logger.Level.INFO, "demo-clock.job_done field=$field date=$date")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "demo-clock.job_done_failed field=$field date=$date — 이동은 이 경계에서 멈춘다", e)
        }
    }

    fun snapshot(): Snapshot {
        val now = game.now(clock)
        return Snapshot(now, clock.instant(), game.shift.duration, DemoClockRules.moveLimit(now, demo.maxSpan))
    }

    private fun rejected(reason: String) =
        BusinessException(ErrorCode.CLOCK_MOVE_REJECTED, "${ErrorCode.CLOCK_MOVE_REJECTED.defaultMessage} $reason")

    /** 앞으로만 반영한다 — 더 작은 값은 버린다(sync 와 move 가 겹쳐도 시각이 뒤로 가지 않는다). */
    @Synchronized
    private fun apply(value: Long) {
        val from = game.shift.micros
        if (value <= from) return
        game.shift.set(value)
        log.log(System.Logger.Level.INFO, "demo-clock.shifted fromUs=$from toUs=$value gameNow=${game.now(clock)}")
    }

    private fun micros(d: Duration): Long = d.truncatedTo(ChronoUnit.MICROS).let { Math.addExact(Math.multiplyExact(it.seconds, 1_000_000L), it.nano / 1_000L) }

    private companion object {
        const val MOVE_ATTEMPTS = 2
        const val JOB_POLL_MS = 100L

        /** core 서비스는 프레임워크 로깅에 기대지 않는다(ArchitectureTest) — JDK System.Logger. */
        val log: System.Logger = System.getLogger(DemoClockService::class.java.name)
    }
}
