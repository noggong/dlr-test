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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 로컬 데모 시계(prd D25 · LLD platform §4.4). `game.demo.enabled=true` 일 때만 [com.delightroom.mission.core.config.CoreConfiguration] 이 만든다.
 *
 * - [start]: 첫 이동량을 `SET NX` 로 두고(먼저 쓴 앱의 값이 정본) 읽어 [GameProperties.shift] 에 반영한 뒤, `sync-interval` 마다 다시 읽는다.
 *   빈을 만들 때 부르므로 잡의 `SmartLifecycle.start` 보다 먼저 시각이 맞는다.
 * - [move]: 앞으로만 · 다음 경계 − `move-margin` 까지(P12). `compareAndSet` 으로 써서 동시 이동이 서로를 덮지 않는다.
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

    /** 한 번 다시 읽기 — 값이 다르면 반영한다. */
    suspend fun syncOnce() {
        val value = store.get() ?: return
        if (value != game.shift.micros) apply(value)
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

    /** 게임 시각을 [target] 으로 옮긴다(P12). 거절이면 `409 CLOCK_MOVE_REJECTED`. 다른 이동과 겹쳐 지면 한 번 더 시도한다. */
    suspend fun move(target: Target): Snapshot {
        repeat(MOVE_ATTEMPTS) {
            val seen = store.get() ?: game.shift.micros
            apply(seen)
            val now = game.now(clock)
            val at = when (target) {
                is Target.Preset -> DemoClockRules.preset(now, target.preset, game)
                is Target.At -> target.at
            }
            val check = DemoClockRules.check(now, at, demo.moveMargin, game)
            if (!check.ok) throw BusinessException(ErrorCode.CLOCK_MOVE_REJECTED, "${ErrorCode.CLOCK_MOVE_REJECTED.defaultMessage} ${check.reason}")
            val next = Math.addExact(seen, micros(Duration.between(now, at)))
            if (store.compareAndSet(seen, next)) {
                apply(next)
                return snapshot()
            }
        }
        throw BusinessException(ErrorCode.CLOCK_MOVE_REJECTED, "${ErrorCode.CLOCK_MOVE_REJECTED.defaultMessage} 다른 이동과 겹쳤다 — 다시 보내 주세요")
    }

    fun snapshot(): Snapshot {
        val now = game.now(clock)
        return Snapshot(now, clock.instant(), game.shift.duration, DemoClockRules.moveLimit(now, demo.moveMargin, game))
    }

    private fun apply(value: Long) {
        val from = game.shift.micros
        if (from == value) return
        game.shift.set(value)
        log.log(System.Logger.Level.INFO, "demo-clock.shifted fromUs=$from toUs=$value gameNow=${game.now(clock)}")
    }

    private fun micros(d: Duration): Long = d.truncatedTo(ChronoUnit.MICROS).let { Math.addExact(Math.multiplyExact(it.seconds, 1_000_000L), it.nano / 1_000L) }

    private companion object {
        const val MOVE_ATTEMPTS = 2

        /** core 서비스는 프레임워크 로깅에 기대지 않는다(ArchitectureTest) — JDK System.Logger. */
        val log: System.Logger = System.getLogger(DemoClockService::class.java.name)
    }
}
