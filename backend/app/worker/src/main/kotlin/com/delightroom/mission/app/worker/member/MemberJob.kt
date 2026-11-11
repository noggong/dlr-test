package com.delightroom.mission.app.worker.member

import com.delightroom.mission.core.domain.game.DemoJob
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.DemoJobAck
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
 * - [run] `D`: `D` · `D+1` 파티션(Q9) → 그룹을 PK 순서로 [MemberProperties.pageSize] 개씩(Q7) → 그 그룹들의 06:50 전 가입자(Q4 — `joinCutoffAt`, prd D24) →
 *   그룹마다 `roster`(동시 [MemberProperties.concurrency] 개 — 한 연결 위 파이프라인) → 2명 이상 그룹의 그룹원 전부 -1 행(Q10).
 *   모든 단계가 멱등이라 몇 번 돌려도 같다 — `api` 가 먼저 만든 명단은 그대로 두고 -1 행만 채운다.
 * - 루프는 [GameClock.now](시각 주입 포함) 다음에 오는 07:00 까지 기다렸다가 돈다. 기동 때는 게임 시각의 오늘 · 내일 파티션을 만들고,
 *   07:00 ~ 08:00 사이에 떴으면 오늘 실행을 바로 한다(07:00 실행 중 죽은 경우의 따라잡기 — LLD §3.5 v5.1).
 * - 루프의 실행은 끝까지 성공(건너뛴 페이지 0)할 때까지 그날 08:00 전까지 통째로 다시 돈다([confirmUntilDeadline]) — 멱등이라 다시 돌아도 같다.
 *   끝나면(성공 · 그만둠) 데모 완료 표시 `member` 를 쓴다(LLD platform §4.4-5 — 데모 시각 이동이 이것을 기다려 08:00 으로 간다).
 * - 재적재(LLD platform §4.2): 명단을 만들 때 그날 PostgreSQL 기록이 있으면 그것으로 그룹 Hash 를 되살리고, 08:00 이 지났으면 마감을 따라잡는다.
 *   끝까지 성공하면 버킷 표시를 쓰고, 다음 07:00 을 기다리는 동안 [MemberProperties.watchInterval] 마다 [watchOnce] 로 표시가 사라졌는지 본다.
 */
@Component
class MemberJob(
    private val groups: GroupRepository,
    private val users: UserRepository,
    private val rosters: RosterRepository,
    private val dailyProgress: DailyProgressStore,
    private val groupDays: GroupDayRepository,
    private val gameClock: GameClock,
    private val properties: MemberProperties,
    /** 데모 시각 이동이 기다리는 완료 표시(LLD platform §4.4-5) — 데모가 꺼지면 아무것도 쓰지 않는다. */
    private val ack: DemoJobAck = DemoJobAck.NONE,
) : SmartLifecycle {
    /**
     * 한 번 실행의 요약(LLD §3.5-5). [created] = 이번 실행이 만든 명단 수(`roster` 의 `created = 1`),
     * [rehydrated] = 그중 PostgreSQL 기록으로 되살린 그룹 수, [caughtUp] = 마감 따라잡기에서 `SKIPPED` 가 아닌 결과를 낸 그룹 수,
     * [failedGroups] = 기록이 규칙 밖이라 건너뛴 그룹 + 따라잡기에 실패한 그룹 + 실행 도중 표시가 사라짐(LLD platform §4.2).
     */
    data class Summary(
        val date: LocalDate,
        val groups: Int,
        val playing: Int,
        val solo: Int,
        val empty: Int,
        val created: Int,
        val failedPages: Int,
        val elapsed: Duration,
        val rehydrated: Int = 0,
        val caughtUp: Int = 0,
        val failedGroups: Int = 0,
    ) {
        /** 건너뛴 페이지 · 그룹 · 따라잡기 실패 · 실행 도중 사라진 표시가 없다 — 루프 · 감시가 다시 돌지 않아도 된다. */
        val complete: Boolean get() = failedPages == 0 && failedGroups == 0
    }

    private class PageResult(
        val playing: Int,
        val solo: Int,
        val empty: Int,
        val created: Int,
        val rehydrated: Int,
        val caughtUp: Int,
        val failedGroups: Int,
    )

    @Volatile private var job: Job? = null

    /** 버킷 표시를 본(직접 썼거나 다 있는 것을 본) 날짜 — 이 날짜의 표시가 사라지면 Redis 를 잃은 것이다([watchOnce]). */
    @Volatile private var markedDate: LocalDate? = null

    /** 마지막 실행이 끝까지 되지 않은 날짜 — 표시는 시작에 쓰므로 다 있어도 감시가 다시 돈다([Summary.complete]). */
    @Volatile private var retryDate: LocalDate? = null

    override fun start() {
        if (!properties.enabled || job != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("member"))
        scope.launch { loop() }
        job = scope.coroutineContext[Job]
        log.info("member.started next={}", gameClock.startAt(nextRunDate(gameClock.now())))
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
        catchUpDate(now)?.let { confirmUntilDeadline(it) }
        while (true) {
            val date = nextRunDate(gameClock.now())
            val at = gameClock.startAt(date)
            // delay 는 단조 시계라 벽시계(시각 주입 포함)로 다시 보고, 아직이면 더 기다린다.
            // 기다리는 동안 지금 게임 날짜(date - 1)의 표시를 감시한다 — 사라지면 Redis 를 잃은 것이라 다시 올린다(LLD platform §4.2)
            val watched = date.minusDays(1)
            // 데모 시각 이동이 있으면 바로 깨어 남은 시간을 다시 잰다(LLD platform §4.4-4)
            while (!gameClock.pauseUntil(at, properties.watchInterval)) {
                guarded("watch", watched) { watchOnce(watched) }
            }
            confirmUntilDeadline(date)
        }
    }

    /**
     * [date] 실행을 건너뛴 페이지 없이 끝날 때까지 그날 08:00 전까지 다시 돈다(사이 [MemberProperties.retryBackoff] 부터 두 배 · 최대 [MAX_BACKOFF]).
     * 08:00 이 지나면 그만둔다 — 마감이 그날 그룹 목록을 이미 읽었다. 그동안 요청은 §3.4 로 명단을 그 자리에서 만든다.
     */
    private suspend fun confirmUntilDeadline(date: LocalDate) {
        var backoff = properties.retryBackoff
        while (true) {
            var done = false
            guarded("run", date) { done = run(date).complete }
            if (done) return ack.done(DemoJob.MEMBER.field(), date)
            if (gameClock.now().plus(backoff) >= gameClock.deadlineAt(date)) {
                log.error("member.gave-up date={} — 08:00 전에 끝내지 못했다", date)
                return ack.done(DemoJob.MEMBER.field(), date)
            }
            delay(backoff.toMillis())
            backoff = minOf(backoff.multipliedBy(2), MAX_BACKOFF)
        }
    }

    /**
     * 감시 한 번(LLD platform §4.2 발동 2) — [date] 의 버킷 표시를 센다. 다 있으면 그 날짜를 「본 날짜」로 두고 `false`.
     * 본 적 있는 날짜의 표시가 하나라도 없으면 Redis 를 잃은 것으로 보고 [run] 을 다시 돈 뒤 `true`(08:00 이 지났어도 — 마감은 따라잡는다).
     * 앞 실행이 끝까지 되지 않았으면([Summary.complete] 거짓 — 따라잡기 실패 등) 표시가 다 있어도 다시 돈다.
     * 본 적 없는 날짜면 돌지 않는다(`false`) — 08:00 뒤 기동 때 `run` 을 하지 않는 규칙(game-session §3.5)을 지킨다.
     * [startAfter] 는 [run] 에 그대로 넘긴다(테스트 — 루프는 늘 처음부터).
     */
    suspend fun watchOnce(date: LocalDate, startAfter: GroupId? = null): Boolean {
        val missing = rosters.countUnmarked(date)
        if (missing > 0 && markedDate != date) return false
        if (missing == 0 && retryDate != date) {
            markedDate = date
            return false
        }
        if (missing > 0) {
            log.warn("member.redis_lost date={} missing={} — 그날 명단 · 그룹 Hash 를 다시 올린다", date, missing)
        } else {
            log.warn("member.rerun date={} — 앞 실행이 끝까지 되지 않았다", date)
        }
        run(date, startAfter)
        return true
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
     * [startAfter] 는 그 그룹 다음부터 돈다(테스트가 잡을 특정 그룹에 바로 닿게 할 때 — 루프는 늘 처음부터).
     */
    suspend fun run(date: LocalDate, startAfter: GroupId? = null): Summary {
        val started = System.nanoTime()
        val cutoff = gameClock.joinCutoffAt(date)
        retrying("partitions") {
            dailyProgress.ensurePartitions(date)
            dailyProgress.ensurePartitions(date.plusDays(1))
        }
        var total = 0
        var playing = 0
        var solo = 0
        var empty = 0
        var created = 0
        var rehydrated = 0
        var caughtUp = 0
        var failedPages = 0
        var failedGroups = 0
        // 표시는 시작할 때 쓴다 — 실행 도중 Redis 가 비면 끝에서 다시 셀 때 빠져 있어 실패로 본다(LLD platform §4.2)
        retrying("mark") { rosters.markConfirmed(date) }
        markedDate = date
        var after: GroupId? = startAfter
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
                rehydrated += r.rehydrated
                caughtUp += r.caughtUp
                failedGroups += r.failedGroups
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
        val lost = retrying("mark-check") { rosters.countUnmarked(date) }
        if (lost > 0) {
            log.warn("member.redis_lost date={} missing={} — 실행 도중 표시가 사라졌다", date, lost)
            failedGroups++
        }
        val summary = Summary(
            date, total, playing, solo, empty, created, failedPages, Duration.ofNanos(System.nanoTime() - started), rehydrated, caughtUp,
            failedGroups,
        )
        retryDate = if (summary.complete) null else date
        log.info(
            "member.done date={} groups={} playing={} solo={} empty={} created={} rehydrated={} caughtUp={} failedPages={} " +
                "failedGroups={} elapsedMs={}",
            date, summary.groups, summary.playing, summary.solo, summary.empty, summary.created, summary.rehydrated, summary.caughtUp,
            summary.failedPages, summary.failedGroups, summary.elapsed.toMillis(),
        )
        return summary
    }

    /**
     * 한 페이지: Q4 한 번 → 2명 이상 그룹의 그날 기록 R1 · R2 한 번(LLD platform §4.2) → `roster` 동시(기록이 있으면 재적재 꼬리) → Q10 한 번.
     * 재적재로 만든 그룹은 지금 ≥ 08:00 이면 마감을 따라잡는다.
     */
    private suspend fun confirmPage(page: List<Pair<GroupId, GroupName>>, date: LocalDate, cutoff: Instant): PageResult {
        val members = users.findMembersJoinedBefore(page.map { it.first }, cutoff)
        val built = page.map { (gid, name) -> DayRoster(gid, date, name, members[gid].orEmpty()) }
        val playingGids = built.filter { it.size >= MIN_PLAYING }.map { it.gid }
        val records = dailyProgress.loadDayStates(date, playingGids)
        val now = gameClock.now()
        val deadlineAt = gameClock.deadlineAt(date)
        // 기록이 규칙 밖인 그룹은 그 그룹만 건너뛴다 — 페이지의 다른 그룹까지 막지 않는다(실패로 세어 다시 돈다)
        val bad = mutableSetOf<GroupId>()
        val restores = built.map { r ->
            if (r.size < MIN_PLAYING) return@map null
            try {
                GroupRestore.of(r, records[r.gid], now, deadlineAt)
            } catch (e: IllegalArgumentException) {
                log.error("member.rehydrate_bad gid={} date={}", r.gid.value, date, e)
                bad += r.gid
                null
            }
        }
        val permits = Semaphore(properties.concurrency)
        val made = coroutineScope {
            built.indices.map { i ->
                async { if (built[i].gid in bad) false else permits.withPermit { rosters.createIfAbsent(built[i], restores[i]) } }
            }.awaitAll()
        }
        built.indices.filter { made[it] && restores[it] != null }.forEach { log.warn("member.rehydrated gid={} date={}", built[it].gid.value, date) }
        // 마감 따라잡기는 기록이 있는 그룹 모두 — 이번에 만들었는지와 상관없다(페이지 재시도 · 앞 실행의 실패에도 빠지지 않게, Lua 는 멱등)
        val restored = built.indices.filter { restores[it] != null }.map { built[it].gid }
        val outcomes = if (restored.isNotEmpty() && gameClock.now() >= deadlineAt) {
            coroutineScope { restored.map { gid -> async { permits.withPermit { catchUpDeadline(gid, date) } } }.awaitAll() }
        } else {
            emptyList()
        }
        // created 와 상관없이 넣는다 — api 가 먼저 만든 명단의 -1 행이 빠졌어도 채운다(§3.5-4)
        val rows = built.filter { it.size >= MIN_PLAYING }.flatMap { r -> r.members.keys.map { it to r.gid } }
        dailyProgress.insertNotStarted(date, rows)
        return PageResult(
            playing = built.count { it.size >= MIN_PLAYING },
            solo = built.count { it.size == 1 },
            empty = built.count { it.size == 0 },
            created = made.count { it },
            rehydrated = built.indices.count { made[it] && restores[it] != null },
            caughtUp = outcomes.count { it != null && it != DeadlineCode.SKIPPED },
            failedGroups = bad.size + outcomes.count { it == null },
        )
    }

    /**
     * 마감 따라잡기(LLD platform §4.2 발동 3) — 08:00 뒤 되살린 그룹은 마감 잡이 이미 지나갔으므로 Lua `deadline` 1차를 한 번 부른다
     * (08:00:03 뒤면 Lua 가 확정까지 한다). 결과 코드를 돌려주고, 끝내 실패하면 null — 실행을 실패로 세어 루프 · 감시가 다시 부른다.
     */
    private suspend fun catchUpDeadline(gid: GroupId, date: LocalDate): DeadlineCode? {
        try {
            repeat(EARLY_RETRIES) {
                val outcome = retrying("deadline") { groupDays.runDeadline(gid, date, DeadlinePhase.FIRST) }
                if (outcome.code != DeadlineCode.EARLY) {
                    log.info("member.caught_up gid={} date={} code={} version={}", gid.value, date, outcome.code, outcome.version)
                    return outcome.code
                }
                // 워커 시계가 Redis 보다 빠름 — deadline-result §3.2 의 EARLY 와 같이 조금 뒤 다시
                delay(EARLY_DELAY_MS)
            }
            log.error("member.catch_up_failed gid={} date={} reason=EARLY", gid.value, date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("member.catch_up_failed gid={} date={}", gid.value, date, e)
        }
        return null
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
        const val EARLY_RETRIES = 200
        const val EARLY_DELAY_MS = 20L
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)

        /** 그룹 Hash · -1 행을 두는 최소 인원(1명 그룹은 결과가 없다 — HLD §5). `roster.lua` 의 n ≥ 2 와 같다. */
        const val MIN_PLAYING = 2
        val log = LoggerFactory.getLogger(MemberJob::class.java)
    }
}
