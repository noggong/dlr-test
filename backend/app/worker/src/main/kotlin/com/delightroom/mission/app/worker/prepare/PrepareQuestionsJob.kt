package com.delightroom.mission.app.worker.prepare

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.question.BankDraw
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.question.QuestionBankBuilder
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.UserRepository
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
 * `worker: prepare-questions` — 06:30 · 06:50 KST 에 유저마다 그날 문제은행을 미리 뽑아 Redis 에 둔다(prd D18 · LLD game-session §3.6).
 *
 * - [run] `(D, i)` = `game.prepare-times` 의 `i` 번째 실행: `prepareAt[i-1] ≤ joined_at < prepareAt[i]` 인 유저(첫 실행은 아래 끝 없음).
 * - 유저를 PK 순서로 [PrepareQuestionsProperties.batchSize] 명씩 읽고(Q5) 그 묶음의 출제 이력을 한 번에 읽어(Q3)
 *   [QuestionBankBuilder] 로 뽑은 뒤 `SET NX PXAT D 08:00` 을 파이프라인으로 보낸다. 고갈 유저는 두지 않는다.
 * - 루프는 [GameClock.now](시각 주입 포함) 다음에 오는 실행 시각까지 기다렸다가 돈다([nextRun]). 기동 때 이미 지난 시각은 돌지 않는다 —
 *   은행이 없으면 `api` 가 요청 때 같은 규칙으로 만든다(D18). 한 묶음이 끝내 실패하면 건너뛰고 다음 묶음으로 간다.
 */
@Component
class PrepareQuestionsJob(
    private val users: UserRepository,
    private val questions: QuestionRepository,
    private val banks: QuestionBankCache,
    private val gameClock: GameClock,
    private val game: GameProperties,
    private val properties: PrepareQuestionsProperties,
) : SmartLifecycle {
    /** 한 번 실행의 요약(LLD §3.6-6). [existing] = 이미 키가 있던 유저(06:30 실행 · `api` 가 만든 것). */
    data class Summary(
        val date: LocalDate,
        val index: Int,
        val targets: Int,
        val created: Int,
        val existing: Int,
        val exhausted: Int,
        val failedBatches: Int,
        val elapsed: Duration,
    ) {
        val usersPerSecond: Long get() = targets * 1_000L / maxOf(1L, elapsed.toMillis())
    }

    /** 실행 하나 — [date] 의 [index] 번째 prepare 시각 [at]. */
    data class Run(val date: LocalDate, val index: Int, val at: Instant)

    @Volatile private var job: Job? = null

    override fun start() {
        if (!properties.enabled || job != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("prepare-questions"))
        scope.launch { loop() }
        job = scope.coroutineContext[Job]
        log.info("prepare-questions.started times={}", game.prepareTimes)
    }

    override fun stop() {
        val running = job ?: return
        runBlocking { running.cancelAndJoin() }
        job = null
    }

    override fun isRunning(): Boolean = job != null

    private suspend fun loop() {
        var last: Run? = null
        while (true) {
            val next = nextRun(gameClock.now(), last)
            // delay 는 단조 시계라 벽시계(시각 주입 포함)로 다시 보고, 아직이면 더 기다린다.
            // 데모 시각 이동이 있으면 바로 깨어 다시 잰다 — 지난 실행은 곧바로 돈다(LLD platform §4.4-4)
            while (!gameClock.pauseUntil(next.at, MAX_PAUSE)) Unit
            try {
                run(next.date, next.index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 이번 실행은 버린다 — 은행이 없는 유저는 api 가 요청 때 만든다(D18)
                log.error("prepare-questions.failed date={} index={}", next.date, next.index, e)
            }
            last = next
        }
    }

    /**
     * 다음 실행. [last](방금 끝난 실행) 다음 순번이 같은 날에 있고 그날 마감 전이면 그 시각이 지났어도 그것이다 —
     * 06:30 실행이 06:50 을 넘겨 끝나도 06:50 실행이 빠지지 않는다(LLD §3.6). 아니면 [now] 보다 뒤인 가장 이른 시각.
     */
    internal fun nextRun(now: Instant, last: Run? = null): Run {
        if (last != null && last.index + 1 < game.prepareTimes.size && now < gameClock.deadlineAt(last.date)) {
            return Run(last.date, last.index + 1, gameClock.prepareAt(last.date)[last.index + 1])
        }
        val today = gameClock.playDate(now)
        for (date in listOf(today, today.plusDays(1))) {
            gameClock.prepareAt(date).forEachIndexed { i, at -> if (at > now) return Run(date, i, at) }
        }
        error("다음 prepare 시각이 없다: ${game.prepareTimes}")
    }

    /** [date] 의 [index] 번째 실행(LLD §3.6). 기다리지 않고 바로 돈다. */
    suspend fun run(date: LocalDate, index: Int): Summary {
        val times = gameClock.prepareAt(date)
        require(index in times.indices) { "prepare 순번은 0 ~ ${times.size - 1} 이다: $index" }
        val from = if (index == 0) null else times[index - 1]
        val to = times[index]
        val expireAt = gameClock.deadlineAt(date)
        val started = System.nanoTime()
        // 잡마다 한 번 — 새로 넣은 문제는 다음 실행부터 들어간다(LLD §5)
        val ids = retrying("questions") { questions.loadIdsByDifficulty() }
        var targets = 0
        var created = 0
        var exhausted = 0
        var failedBatches = 0
        var failedUsers = 0
        var after: UserId? = null
        while (true) {
            // 대상 읽기가 끝내 실패하면 다음 페이지를 알 수 없어 실행을 멈춘다(예외가 루프로 간다)
            val page = retrying("users") { users.findIdsJoinedIn(from, to, after, properties.batchSize) }
            if (page.isEmpty()) break
            targets += page.size
            try {
                // 다시 해도 같다 — NX 라 앞 시도에서 둔 키는 「이미 있음」으로 세일 뿐이다
                val (c, e) = retrying("batch") { prepareBatch(page, ids, date, expireAt) }
                created += c
                exhausted += e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedBatches++
                failedUsers += page.size
                log.warn("prepare-questions.batch-skipped date={} index={} from={} size={}", date, index, page.first().value, page.size, e)
            }
            if (page.size < properties.batchSize) break
            after = page.last()
        }
        val summary = Summary(
            date = date,
            index = index,
            targets = targets,
            created = created,
            existing = targets - exhausted - created - failedUsers,
            exhausted = exhausted,
            failedBatches = failedBatches,
            elapsed = Duration.ofNanos(System.nanoTime() - started),
        )
        log.info(
            "prepare-questions.done date={} at={} targets={} created={} existing={} exhausted={} failedBatches={} elapsedMs={} usersPerSec={}",
            date, game.prepareTimes[index], summary.targets, summary.created, summary.existing, summary.exhausted,
            summary.failedBatches, summary.elapsed.toMillis(), summary.usersPerSecond,
        )
        return summary
    }

    /** 한 묶음: 출제 이력(Q3) → 뽑기 → `SET NX PXAT` 파이프라인. (새로 둔 수, 고갈 수). */
    private suspend fun prepareBatch(
        page: List<UserId>,
        ids: Map<Difficulty, LongArray>,
        date: LocalDate,
        expireAt: Instant,
    ): Pair<Int, Int> {
        val served = questions.findServedIds(page)
        val built = LinkedHashMap<UserId, QuestionBank>(page.size * 2)
        var exhausted = 0
        for (uid in page) {
            when (val draw = QuestionBankBuilder.build(ids, served[uid].orEmpty(), game.bankSizePerDifficulty)) {
                is BankDraw.Built -> built[uid] = draw.bank
                is BankDraw.Exhausted -> exhausted++
            }
        }
        return banks.putAllIfAbsent(date, built, expireAt) to exhausted
    }

    /** [RETRIES] 번까지 — 사이에 [PrepareQuestionsProperties.retryBackoff] 부터 두 배씩 쉰다. */
    private suspend fun <T> retrying(what: String, block: suspend () -> T): T {
        var backoff = properties.retryBackoff
        repeat(RETRIES - 1) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("prepare-questions.retry what={} attempt={} cause={}", what, attempt + 1, e.toString())
            }
            delay(backoff.toMillis())
            backoff = backoff.multipliedBy(2)
        }
        return block()
    }

    private companion object {
        const val RETRIES = 3
        val log = LoggerFactory.getLogger(PrepareQuestionsJob::class.java)

        /** 한 번에 기다리는 상한 — 벽시계로 다시 잰다(시각 이동은 상한과 무관하게 바로 깨운다). */
        val MAX_PAUSE: Duration = Duration.ofMinutes(1)
    }
}
