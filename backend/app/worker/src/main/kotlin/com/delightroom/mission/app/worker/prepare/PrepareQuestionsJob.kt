package com.delightroom.mission.app.worker.prepare

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.question.BankDraw
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
 * - 루프는 [GameClock.now](시각 주입 포함) 다음에 오는 실행 시각까지 기다렸다가 돈다. 기동 때 이미 지난 시각은 돌지 않는다 —
 *   은행이 없으면 `api` 가 요청 때 같은 규칙으로 만든다(D18).
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
    /** 한 번 실행의 요약(LLD §3.6-6). */
    data class Summary(
        val date: LocalDate,
        val index: Int,
        val targets: Int,
        val created: Int,
        val existing: Int,
        val exhausted: Int,
        val elapsed: Duration,
    ) {
        val usersPerSecond: Long get() = if (elapsed.isZero) targets.toLong() else targets * 1_000L / maxOf(1, elapsed.toMillis())
    }

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
        while (true) {
            val now = gameClock.now()
            val (date, index, at) = nextRun(now)
            delay(Duration.between(now, at).toMillis().coerceAtLeast(0))
            try {
                run(date, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 이번 실행은 버린다 — 은행이 없는 유저는 api 가 요청 때 만든다(D18)
                log.error("prepare-questions.failed date={} index={}", date, index, e)
            }
        }
    }

    /** [now] 보다 뒤인 가장 이른 실행 — (날짜, 순번, 시각). */
    internal fun nextRun(now: Instant): Triple<LocalDate, Int, Instant> {
        val today = gameClock.playDate(now)
        for (date in listOf(today, today.plusDays(1))) {
            gameClock.prepareAt(date).forEachIndexed { i, at -> if (at > now) return Triple(date, i, at) }
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
        val ids = questions.loadIdsByDifficulty()
        var targets = 0
        var created = 0
        var exhausted = 0
        var after: UserId? = null
        while (true) {
            val page = users.findIdsJoinedIn(from, to, after, properties.batchSize)
            if (page.isEmpty()) break
            targets += page.size
            val served = questions.findServedIds(page)
            val built = LinkedHashMap<UserId, QuestionBank>(page.size * 2)
            for (uid in page) {
                when (val draw = QuestionBankBuilder.build(ids, served[uid].orEmpty(), game.bankSizePerDifficulty)) {
                    is BankDraw.Built -> built[uid] = draw.bank
                    is BankDraw.Exhausted -> exhausted++
                }
            }
            created += banks.putAllIfAbsent(date, built, expireAt)
            if (page.size < properties.batchSize) break
            after = page.last()
        }
        val summary = Summary(
            date = date,
            index = index,
            targets = targets,
            created = created,
            existing = targets - exhausted - created,
            exhausted = exhausted,
            elapsed = Duration.ofNanos(System.nanoTime() - started),
        )
        log.info(
            "prepare-questions.done date={} at={} targets={} created={} existing={} exhausted={} elapsedMs={} usersPerSec={}",
            date, game.prepareTimes[index], summary.targets, summary.created, summary.existing, summary.exhausted,
            summary.elapsed.toMillis(), summary.usersPerSecond,
        )
        return summary
    }

    private companion object {
        val log = LoggerFactory.getLogger(PrepareQuestionsJob::class.java)
    }
}
