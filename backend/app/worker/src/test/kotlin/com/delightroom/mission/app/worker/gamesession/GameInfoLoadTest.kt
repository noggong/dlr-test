package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.service.GameService
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
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
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import java.time.LocalTime

/**
 * 07:00 전 게임 정보의 PostgreSQL 부하(ORACLE game-session O35 — LLD §3.1-3 · §5). 시계를 `D 06:59` 에 두고 게임 정보 1,000건
 * (그룹 100개 × 그룹원 10명이 한 번씩)을 부른 뒤 `pg_stat_statements` 의 `calls` 증가를 센다. worker 에는 HTTP 계층이 없어
 * [GameService.gameInfo] 를 직접 부른다(07:00 전 길은 Q4 · Q6 만 — 문제 캐시를 쓰지 않는다). 동시 50.
 * 「1건당」은 단독 1건의 증가와 1,000건 평균 둘로 본다(동시 요청은 건마다 가를 수 없다).
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
@Import(MutableClockConfiguration::class)
class GameInfoLoadTest
    @Autowired
    constructor(
        private val service: GameService,
        private val gameClock: GameClock,
        private val clock: MutableClock,
        db: DatabaseClient,
        connectionFactory: ConnectionFactory,
    ) {
        private val fixture = LoadFixture(db, connectionFactory)
        private val d: LocalDate = LocalDate.now(LoadFixture.KST).plusDays(11)

        @Test
        fun `O35 06시59분 게임 정보 1000건 1건당 PostgreSQL 쿼리 2 이하`(): Unit = runBlocking {
            fixture.schema()
            val joinedAt = d.minusDays(1).atTime(LocalTime.of(6, 0)).atZone(LoadFixture.KST).toOffsetDateTime()
            val members = fixture.groups(LoadFixture.prefix("L35"), GROUPS, GROUP_SIZE, joinedAt)
            clock.set(d.atTime(LocalTime.of(6, 59)).atZone(LoadFixture.KST).toInstant())
            assertThat(gameClock.infoDate(gameClock.now())).isEqualTo(d)
            assertThat(gameClock.now()).isBefore(gameClock.startAt(d))
            val requests = members.flatMap { (gid, uids) -> uids.map { gid } }
            assertThat(requests).hasSize(REQUESTS)

            fixture.warmPool(CONCURRENCY)
            // 단독 1건 — 요청 하나가 부르는 쿼리 수
            fixture.resetStatements()
            val single = fixture.calls().let { before -> service.gameInfo(requests.first()); fixture.calls() - before }

            fixture.resetStatements()
            val before = fixture.calls()
            val limit = Semaphore(CONCURRENCY)
            val infos = withContext(Dispatchers.Default) {
                requests.map { gid -> async { limit.withPermit { service.gameInfo(gid) } } }.awaitAll()
            }
            val delta = fixture.calls() - before
            val statements = fixture.statements()

            println("[O35] single=$single requests=${infos.size} pgCallsDelta=$delta perRequest=${delta.toDouble() / infos.size} statements=$statements")
            assertThat(infos.map { it.members.size }).containsOnly(GROUP_SIZE)
            assertThat(infos.map { it.date }).containsOnly(d)
            assertThat(delta).describedAs("PostgreSQL calls 증가 — $statements").isLessThanOrEqualTo(2L * REQUESTS)
            assertThat(single).describedAs("단독 1건의 쿼리").isBetween(1L, 2L)
            assertThat(delta.toDouble() / REQUESTS).describedAs("1건당 쿼리(평균)").isLessThanOrEqualTo(2.0)
            fixture.assertCounting()
        }

        private companion object {
            const val GROUPS = 100
            const val GROUP_SIZE = 10
            const val REQUESTS = GROUPS * GROUP_SIZE
            const val CONCURRENCY = 50
        }
    }
