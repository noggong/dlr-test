package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.question.BankDraw
import com.delightroom.mission.core.domain.question.QuestionBankBuilder
import com.delightroom.mission.core.domain.question.QuestionCatalog
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.GameService
import com.delightroom.mission.core.service.RosterService
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 07:00 문제은행은 PostgreSQL 을 거치지 않는다(ORACLE game-session O34 — HLD §1 목표 · D18 · LLD §3 공통).
 *
 * 그룹 1,000개 × 10명의 명단(`worker: member` 몫)과 은행(`worker: prepare-questions` 몫)을 미리 만든 뒤 `pg_stat_statements` 를 비우고,
 * 10,000명이 동시 200 으로 문제은행(= 미션 시작)을 요청한다. worker 에는 HTTP 계층이 없어 [GameService.startMission] 을 직접 부른다 —
 * PostgreSQL 호출은 서비스 아래(명단 · 은행 대체 경로)에서만 나므로 판정이 같다. 문제 캐시는 api 만 적재하므로 테스트가 Q1 로 만든다.
 * 시각은 `game.clock-offset` 으로 [D] 07:05 — Lua `start` 의 창 안이다.
 */
@EnabledIfSystemProperty(named = "game.load", matches = "true")
@SpringBootTest(
    properties = [
        "worker.persister.enabled=false",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class QuestionBankRequestLoadTest
    @Autowired
    constructor(
        private val users: UserRepository,
        private val groups: GroupRepository,
        private val questions: QuestionRepository,
        private val progresses: ProgressRepository,
        private val properties: GameProperties,
        private val clock: Clock,
        private val gameClock: GameClock,
        private val rosters: RosterService,
        private val cache: QuestionBankCache,
        db: DatabaseClient,
        connectionFactory: ConnectionFactory,
    ) {
        private val fixture = LoadFixture(db, connectionFactory)

        @Test
        fun `O34 명단 은행을 미리 만든 10000명 동시 200 문제은행에서 PostgreSQL calls 증가 0`(): Unit = runBlocking {
            fixture.schema()
            fixture.partitions(D)
            val joinedAt = D.minusDays(1).atTime(LocalTime.of(6, 0)).atZone(LoadFixture.KST).toOffsetDateTime()
            val members = fixture.groups(LoadFixture.prefix("L34"), GROUPS, GROUP_SIZE, joinedAt)
            val catalog = QuestionCatalog(questions.loadAll())
            val service = GameService(users, groups, questions, progresses, properties, clock, gameClock, rosters, cache, catalog)
            assertThat(gameClock.playDate(gameClock.now())).isEqualTo(D)

            // 미리 만들기 — 07:00 전의 worker 몫(명단 Q4 · Q6 · Q10, 은행 Q3)
            val limit = Semaphore(CONCURRENCY)
            withContext(Dispatchers.Default) {
                members.keys.map { gid -> async { limit.withPermit { rosters.findOrCreate(gid, D) } } }.awaitAll()
            }
            for (chunk in members.values.flatten().chunked(1_000)) {
                val served = questions.findServedIds(chunk)
                val banks = chunk.associateWith { uid ->
                    when (val draw = QuestionBankBuilder.build(catalog.idsByDifficulty, served[uid].orEmpty(), properties.bankSizePerDifficulty)) {
                        is BankDraw.Built -> draw.bank
                        is BankDraw.Exhausted -> error("고갈일 수 없다: $draw")
                    }
                }
                cache.putAllIfAbsent(D, banks, gameClock.deadlineAt(D))
            }

            fixture.warmPool(CONCURRENCY)
            fixture.resetStatements()
            val before = fixture.calls()
            val started = System.nanoTime()
            val results = withContext(Dispatchers.Default) {
                members.flatMap { (gid, uids) -> uids.map { gid to it } }.map { (gid, uid) ->
                    async { limit.withPermit { runCatching { service.startMission(uid, gid) } } }
                }.awaitAll()
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val after = fixture.calls()
            val statements = fixture.statements()

            val failures = results.mapNotNull { it.exceptionOrNull() }
            println(
                "[O34] requests=${results.size} ok=${results.size - failures.size} elapsedMs=$elapsedMs " +
                    "pgCallsDelta=${after - before} statements=$statements",
            )
            assertThat(failures).describedAs("실패한 요청").isEmpty()
            assertThat(results.map { it.getOrThrow().questions.size }).containsOnly(3 * properties.bankSizePerDifficulty)
            assertThat(after - before).describedAs("PostgreSQL calls 증가 — $statements").isZero()
            // 「0」이 집계가 꺼져서 나온 것이 아니다
            fixture.assertCounting()
        }

        companion object {
            private const val GROUPS = 1_000
            private const val GROUP_SIZE = 10
            private const val CONCURRENCY = 200

            /** 다른 테스트의 날짜와 겹치지 않는 날 — 명단 Q10 이 그날 파티션에 쓴다. */
            private val D: LocalDate = LocalDate.now(LoadFixture.KST).plusDays(10)

            @JvmStatic
            @DynamicPropertySource
            fun offset(registry: DynamicPropertyRegistry) {
                val at = D.atTime(LocalTime.of(7, 5)).atZone(LoadFixture.KST).toInstant()
                registry.add("game.clock-offset") { Duration.between(Instant.now(), at).toString() }
            }
        }
    }
