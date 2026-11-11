package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.domain.result.DeadlineReport
import com.delightroom.mission.core.service.DeadlineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * `worker: deadline` 스케줄러(LLD deadline-result §3.2) — 매일 08:00:00 에 1차, 08:00:03 에 2차를 **따로** 띄운다.
 *
 * - 「지금」은 [GameClock.now](`Clock` + `game.clock-offset`). 남은 시간을 다시 재며 최대 [MAX_SLEEP] 씩 기다린다.
 * - 기동 때 08:00 이 이미 지난 가장 늦은 날짜([catchUpDate])가 있으면 그날 1차는 곧바로, 2차는 `max(지금, 08:00:03)` 에 다시 돈다 —
 *   Lua 가 멱등이라 이미 처리된 그룹은 `SKIPPED` 다.
 * - 단계는 각자 코루틴으로 돌고, 스케줄러는 끝나기를 기다리지 않고 다음 시각을 기다린다.
 */
@Component
class DeadlineScheduler(
    private val service: DeadlineService,
    private val clock: GameClock,
    private val game: GameProperties,
    private val properties: DeadlineProperties,
) : SmartLifecycle {
    @Volatile private var job: Job? = null

    override fun start() {
        if (!properties.enabled || job != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("deadline"))
        scope.launch { schedule(scope) }
        job = scope.coroutineContext[Job]
        log.info(
            "deadline.started buckets={} instance={}/{} first={} last={}",
            service.buckets.size, properties.instanceIndex, properties.instanceCount, service.buckets.firstOrNull(), service.buckets.lastOrNull(),
        )
    }

    override fun stop() {
        val running = job ?: return
        // 도중에 끊긴 단계는 다음 기동의 다시 돌기(멱등)가 마저 한다
        runBlocking { running.cancelAndJoin() }
        job = null
        log.info("deadline.stopped")
    }

    override fun isRunning(): Boolean = job != null

    /** 기동 때 다시 돌 날짜 — 08:00 이 지난 가장 늦은 날짜 `d` 이고 `now < d+1 의 07:00` 일 때만(LLD §3.2 7). */
    fun catchUpDate(now: Instant): LocalDate? {
        val today = clock.playDate(now)
        val date = if (clock.deadlineAt(today) <= now) today else today.minusDays(1)
        return date.takeIf { now < clock.startAt(it.plusDays(1)) }
    }

    /** 08:00 이 아직 오지 않은 가장 이른 날짜. */
    fun nextDate(now: Instant): LocalDate {
        val today = clock.playDate(now)
        return if (now < clock.deadlineAt(today)) today else today.plusDays(1)
    }

    private suspend fun schedule(scope: CoroutineScope) {
        val now = clock.now()
        val catchUp = catchUpDate(now)
        var date = if (catchUp != null) {
            log.info("deadline.catch_up date={}", catchUp)
            runDay(scope, catchUp)
            catchUp.plusDays(1)
        } else {
            nextDate(now)
        }
        while (true) {
            runDay(scope, date)
            date = date.plusDays(1)
        }
    }

    /** [date] 의 08:00:00 까지 기다려 1차, 08:00:03 까지 기다려 2차를 띄운다(이미 지났으면 곧바로). */
    private suspend fun runDay(scope: CoroutineScope, date: LocalDate) {
        sleepUntil(clock.deadlineAt(date))
        launchPhase(scope, date, DeadlinePhase.FIRST)
        sleepUntil(game.graceEndAt(date))
        launchPhase(scope, date, DeadlinePhase.SECOND)
    }

    private suspend fun sleepUntil(at: Instant) {
        while (true) {
            val left = Duration.between(clock.now(), at)
            if (left <= Duration.ZERO) return
            delay(minOf(left, MAX_SLEEP).toMillis().coerceAtLeast(1))
        }
    }

    private fun launchPhase(scope: CoroutineScope, date: LocalDate, phase: DeadlinePhase) {
        scope.launch(CoroutineName("deadline-$date-${phase.value}")) {
            var backoff = properties.phaseRetryBackoff
            while (true) {
                val clean = try {
                    service.runPhase(date, phase).also(::report).let { it.failedGroups.isEmpty() && it.failedBuckets.isEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("deadline.phase_error date={} phase={}", date, phase.value, e)
                    false
                }
                // 1차 실패는 08:00:03 의 2차가 덮는다. 2차 실패는 다음 날 07:00 전까지 다시 돈다(LLD §3.2 8 · 멱등)
                if (clean || phase != DeadlinePhase.SECOND) return@launch
                if (clock.now().plus(backoff) >= clock.startAt(date.plusDays(1))) {
                    log.error("deadline.phase_gave_up date={} phase={}", date, phase.value)
                    return@launch
                }
                log.warn("deadline.phase_retry date={} phase={} afterMs={}", date, phase.value, backoff.toMillis())
                delay(backoff.toMillis())
                backoff = minOf(backoff.multipliedBy(2), properties.phaseRetryBackoffMax)
            }
        }
    }

    private fun report(r: DeadlineReport) {
        r.failedGroups.forEach {
            log.warn("deadline.group_failed gid={} date={} phase={} reason={}", it.gid.value, r.date, r.phase.value, it.reason)
        }
        r.failedBuckets.forEach { log.warn("deadline.bucket_failed bucket={} date={} phase={}", it, r.date, r.phase.value) }
        log.info(
            "deadline.phase_done date={} phase={} buckets={} groups={} pending={} decided={} decidedFinal={} finalized={} " +
                "skipped={} noGroup={} failed={} failedBuckets={} elapsedMs={}",
            r.date, r.phase.value, r.buckets, r.groups,
            r.count(DeadlineCode.PENDING), r.count(DeadlineCode.DECIDED), r.count(DeadlineCode.DECIDED_FINAL),
            r.count(DeadlineCode.FINALIZED), r.count(DeadlineCode.SKIPPED), r.count(DeadlineCode.NO_GROUP),
            r.failedGroups.size, r.failedBuckets.size, r.elapsed.toMillis(),
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DeadlineScheduler::class.java)
        val MAX_SLEEP: Duration = Duration.ofMinutes(1)
    }
}
