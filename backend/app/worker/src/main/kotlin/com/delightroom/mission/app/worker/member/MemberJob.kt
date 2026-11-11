package com.delightroom.mission.app.worker.member

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * `worker: member` — 매일 07:00:00 KST 그날 명단 확정 · 하루 파티션(LLD game-session §3.5 · LLD platform 구현 순서 2).
 *
 * - [run] `D`: `D` · `D+1` 파티션(Q9) → 그룹을 PK 순서로 [MemberProperties.pageSize] 개씩(Q7) → 그 그룹들의 07:00 전 가입자(Q4) →
 *   그룹마다 `roster`(동시 [MemberProperties.concurrency] 개 — 한 연결 위 파이프라인) → 2명 이상 그룹의 그룹원 전부 -1 행(Q10).
 *   모든 단계가 멱등이라 몇 번 돌려도 같다 — `api` 가 먼저 만든 명단은 그대로 두고 -1 행만 채운다.
 * - 루프는 [GameClock.now](시각 주입 포함) 다음에 오는 07:00 까지 기다렸다가 돈다. 기동 때는 게임 시각의 오늘 · 내일 파티션을 만들고,
 *   07:00 ~ 08:00 사이에 떴으면 오늘 실행을 바로 한 번 한다(07:00 실행 중 죽은 경우의 따라잡기 — LLD §3.5 v5.1).
 */
@Component
class MemberJob(
    private val groups: GroupRepository,
    private val users: UserRepository,
    private val rosters: RosterRepository,
    private val dailyProgress: DailyProgressStore,
    private val gameClock: GameClock,
    private val properties: MemberProperties,
) : SmartLifecycle {
    /** 한 번 실행의 요약(LLD §3.5-5). [created] = 이번 실행이 만든 명단 수(`roster` 의 `created = 1`). */
    data class Summary(
        val date: LocalDate,
        val groups: Int,
        val playing: Int,
        val solo: Int,
        val empty: Int,
        val created: Int,
        val failedPages: Int,
        val elapsed: Duration,
    )

    private class PageResult(val playing: Int, val solo: Int, val empty: Int, val created: Int)

    @Volatile private var job: Job? = null

    override fun start() {
        if (!properties.enabled || job != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("member"))
        scope.launch { loop() }
        job = scope.coroutineContext[Job]
        log.info("member.started startTime={}", gameClock.startAt(gameClock.playDate(gameClock.now())))
    }

    override fun stop() {
        val running = job ?: return
        runBlocking { running.cancelAndJoin() }
        job = null
    }

    override fun isRunning(): Boolean = job != null

    private suspend fun loop() {
        val now = gameClock.now()
        val today = gameClock.playDate(now)
        guarded("startup-partitions", today) {
            retrying("partitions") {
                dailyProgress.ensurePartitions(today)
                dailyProgress.ensurePartitions(today.plusDays(1))
            }
        }
        catchUpDate(now)?.let { guarded("catch-up", it) { run(it) } }
        while (true) {
            val date = nextRunDate(gameClock.now())
            val at = gameClock.startAt(date)
            // delay 는 단조 시계라 벽시계(시각 주입 포함)로 다시 보고, 아직이면 더 기다린다
            while (true) {
                val wait = Duration.between(gameClock.now(), at)
                if (wait.isNegative || wait.isZero) break
                delay(wait.toMillis().coerceAtLeast(1))
            }
            guarded("run", date) { run(date) }
        }
    }

    /** 다음 실행 날짜 — [now] 보다 뒤인 가장 이른 07:00 의 날짜. */
    internal fun nextRunDate(now: Instant): LocalDate {
        val today = gameClock.playDate(now)
        return if (now < gameClock.startAt(today)) today else today.plusDays(1)
    }

    /** 기동 때 바로 돌 날짜 — 오늘 07:00 ~ 08:00 이면 오늘, 아니면 없음(08:00 뒤에는 마감이 이미 그날 그룹 목록을 읽었다). */
    internal fun catchUpDate(now: Instant): LocalDate? {
        val today = gameClock.playDate(now)
        return today.takeIf { now >= gameClock.startAt(it) && now < gameClock.deadlineAt(it) }
    }

    /**
     * [date] 의 명단 확정(LLD §3.5). 기다리지 않고 바로 돌고, 시각을 검사하지 않는다 — 루프가 `D 07:00` 뒤에만 부른다.
     * 파티션 · 페이지 읽기가 끝내 실패하면 예외로 끝나고, 한 페이지 처리가 끝내 실패하면 그 페이지를 건너뛴다.
     */
    suspend fun run(date: LocalDate): Summary {
        val started = System.nanoTime()
        val cutoff = gameClock.startAt(date)
        retrying("partitions") {
            dailyProgress.ensurePartitions(date)
            dailyProgress.ensurePartitions(date.plusDays(1))
        }
        var total = 0
        var playing = 0
        var solo = 0
        var empty = 0
        var created = 0
        var failedPages = 0
        var after: GroupId? = null
        while (true) {
            val page = retrying("groups") { groups.findPage(after, properties.pageSize) }
            if (page.isEmpty()) break
            total += page.size
            try {
                // 다시 해도 같다 — roster 는 없을 때만, Q10 은 ON CONFLICT DO NOTHING
                val r = retrying("page") { confirmPage(page, date, cutoff) }
                playing += r.playing
                solo += r.solo
                empty += r.empty
                created += r.created
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 이 페이지 그룹은 요청 때 RosterService 가 명단을 만든다(§3.4)
                failedPages++
                log.warn("member.page-skipped date={} from={} size={}", date, page.first().first.value, page.size, e)
            }
            if (page.size < properties.pageSize) break
            after = page.last().first
        }
        val summary = Summary(date, total, playing, solo, empty, created, failedPages, Duration.ofNanos(System.nanoTime() - started))
        log.info(
            "member.done date={} groups={} playing={} solo={} empty={} created={} failedPages={} elapsedMs={}",
            date, summary.groups, summary.playing, summary.solo, summary.empty, summary.created, summary.failedPages,
            summary.elapsed.toMillis(),
        )
        return summary
    }

    /** 한 페이지: Q4 한 번 → `roster` 동시 → Q10 한 번. */
    private suspend fun confirmPage(page: List<Pair<GroupId, GroupName>>, date: LocalDate, cutoff: Instant): PageResult {
        val members = users.findMembersJoinedBefore(page.map { it.first }, cutoff)
        val built = page.map { (gid, name) -> DayRoster(gid, date, name, members[gid].orEmpty()) }
        val permits = Semaphore(properties.concurrency)
        val made = coroutineScope {
            built.map { roster -> async { permits.withPermit { rosters.createIfAbsent(roster) } } }.awaitAll()
        }
        // created 와 상관없이 넣는다 — api 가 먼저 만든 명단의 -1 행이 빠졌어도 채운다(§3.5-4)
        val rows = built.filter { it.size >= MIN_PLAYING }.flatMap { r -> r.members.keys.map { it to r.gid } }
        dailyProgress.insertNotStarted(date, rows)
        return PageResult(
            playing = built.count { it.size >= MIN_PLAYING },
            solo = built.count { it.size == 1 },
            empty = built.count { it.size == 0 },
            created = made.count { it },
        )
    }

    /** 루프의 한 단계 — 실패는 로그만 남기고 다음 07:00 을 기다린다(그동안 요청은 §3.4 로 명단을 만든다). */
    private suspend fun guarded(what: String, date: LocalDate, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("member.failed step={} date={}", what, date, e)
        }
    }

    /** [RETRIES] 번까지 — 사이에 [MemberProperties.retryBackoff] 부터 두 배씩 쉰다. */
    private suspend fun <T> retrying(what: String, block: suspend () -> T): T {
        var backoff = properties.retryBackoff
        repeat(RETRIES - 1) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("member.retry what={} attempt={} cause={}", what, attempt + 1, e.toString())
            }
            delay(backoff.toMillis())
            backoff = backoff.multipliedBy(2)
        }
        return block()
    }

    private companion object {
        const val RETRIES = 3

        /** 그룹 Hash · -1 행을 두는 최소 인원(1명 그룹은 결과가 없다 — HLD §5). `roster.lua` 의 n ≥ 2 와 같다. */
        const val MIN_PLAYING = 2
        val log = LoggerFactory.getLogger(MemberJob::class.java)
    }
}
