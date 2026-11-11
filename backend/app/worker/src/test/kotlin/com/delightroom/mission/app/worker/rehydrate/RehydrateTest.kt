package com.delightroom.mission.app.worker.rehydrate

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.app.worker.persister.StreamPersister
import com.delightroom.mission.app.worker.persister.eventually
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.progress.PassSignal
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.progress.SignalOutcome
import com.delightroom.mission.core.domain.progress.SolveSignal
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.service.RosterService
import com.delightroom.mission.core.service.SignalService
import com.delightroom.mission.infra.redis.RedisKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 게임 중 Redis 를 비운 뒤의 재적재(ORACLE platform O19 ~ O21 — LLD platform §4.1).
 *
 * 게임 시각은 `D 07:30`(오프셋). 신호는 Lua(`start` · `shown` 은 저장소 포트, 풀이 · 패스 · 부정 실패는 [SignalService])로 보내고,
 * 스프링이 띄운 persister 가 PostgreSQL 에 다 쓴 뒤([RehydrateFixture.drained]) `FLUSHALL` 한다.
 * `createIfAbsent` 가 `true` 를 낸 횟수는 [Counting] 이 그룹마다 센다(O21).
 */
@SpringBootTest(
    properties = [
        "game.bucket-count=8",
        "spring.sql.init.mode=always",
        "worker.persister.idle-delay=20ms",
        "worker.persister.retry-backoff=100ms",
        "worker.persister.retry-backoff-max=500ms",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.FreshRedisInitializer::class])
@Import(RehydrateTest.Counting::class)
@DirtiesContext
class RehydrateTest
    @Autowired
    constructor(
        private val rosterService: RosterService,
        private val signals: SignalService,
        private val progress: ProgressRepository,
        private val job: MemberJob,
        private val persister: StreamPersister,
        private val gameClock: GameClock,
        keys: RedisKeys,
        redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
    ) {
        @TestConfiguration
        class Counting {
            companion object {
                // BeanPostProcessor 는 다른 빈보다 먼저 만들어지므로 정적 팩토리로 둔다
                @Bean
                @JvmStatic
                fun countingRosters(): BeanPostProcessor = object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                        if (bean is RosterRepository) CountingRosterRepository(bean) else bean
                }
            }
        }

        class CountingRosterRepository(private val delegate: RosterRepository) : RosterRepository by delegate {
            override suspend fun createIfAbsent(roster: DayRoster, restore: GroupRestore?): Boolean =
                delegate.createIfAbsent(roster, restore).also { if (it) created.computeIfAbsent(roster.gid) { AtomicInteger() }.incrementAndGet() }
        }

        private val fx = RehydrateFixture(db, redis, keys, D)

        @BeforeEach
        fun running() {
            assertThat(persister.isRunning).isTrue()
        }

        /** 게임을 진행한 그룹 둘 — A(진행 중 넷) · B(둘 다 3번까지 → 결과 SUCCEEDED). */
        private class Played(val a: TestGroup, val b: TestGroup, val q1: List<Q>, val q2: List<Q>, val q3: List<Q>)

        private fun uid(v: Long) = UserId(v)

        private suspend fun start(g: TestGroup, u: Long) {
            val r = progress.startMission(uid(u), g.gid, D)
            assertThat(r).describedAs("start $u").isInstanceOf(MissionStartOutcome.Started::class.java)
        }

        private suspend fun shown(g: TestGroup, u: Long, no: Int, q: Q): ShownOutcome =
            progress.markShown(uid(u), g.gid, D, no, QuestionId(q.pk), q.id)

        private suspend fun solve(g: TestGroup, u: Long, no: Int, q: Q) =
            signals.solve(uid(u), g.gid, SolveSignal(no, QuestionId(q.pk), "${D}T07:29:59+09:00", gameClock.now()))

        private suspend fun play(): Played {
            val q1 = fx.questions(1)
            val q2 = fx.questions(2)
            val q3 = fx.questions(3)
            val a = fx.group(4)
            val b = fx.group(2)
            rosterService.findOrCreate(a.gid, D)
            rosterService.findOrCreate(b.gid, D)
            val (a1, a2, a3) = a.uids
            listOf(a1, a2, a3).forEach { start(a, it) }
            // a1: 1 · 2번 풀이 → 3번 진입(지금 문제)
            shown(a, a1, 1, q1[0]); solve(a, a1, 1, q1[0])
            shown(a, a1, 2, q2[0]); solve(a, a1, 2, q2[0])
            shown(a, a1, 3, q3[0])
            // a2: 1번 진입 → 패스 → 교체 문제 진입
            shown(a, a2, 1, q1[0])
            signals.pass(uid(a2), a.gid, PassSignal(1, QuestionId(q1[0].pk)))
            assertThat(shown(a, a2, 1, q1[1])).isInstanceOf(ShownOutcome.Shown::class.java)
            // a3: 1번 풀이 → 2번 진입 → 부정 실패(지금 문제는 그대로 남는다)
            shown(a, a3, 1, q1[0]); solve(a, a3, 1, q1[0])
            shown(a, a3, 2, q2[0])
            signals.failByCheat(uid(a3), a.gid)
            // B: 둘 다 3번까지 — 결과 SUCCEEDED(08:00 전 확정 → rankingFinal 1)
            b.uids.forEach { u ->
                start(b, u)
                listOf(q1, q2, q3).forEachIndexed { i, qs ->
                    shown(b, u, i + 1, qs[0]); solve(b, u, i + 1, qs[0])
                }
            }
            assertThat(fx.group(b)).containsEntry("result", "SUCCEEDED").containsEntry("rankingFinal", "1")
            eventually(what = "persister 가 A · B 를 끝까지 씀") { fx.drained(a) && fx.drained(b) }
            return Played(a, b, q1, q2, q3)
        }

        @Test
        fun `O19 게임 중 FLUSHALL 뒤 재적재 — 명단 · 그룹원 칸 · 결과 · 랭킹이 FLUSH 전과 같고 version 은 재적재 시각 ms`(): Unit = runBlocking {
            val p = play()
            val rosterA = fx.roster(p.a)
            val rosterB = fx.roster(p.b)
            val beforeA = fx.group(p.a)
            val beforeB = fx.group(p.b)
            // 시나리오가 칸을 다 건드렸는지(되살릴 것이 실제로 있었는지)
            val (a1, a2, a3, a4) = p.a.uids
            assertThat(beforeA).containsKeys("$a1:cur", "$a1:ls", "$a1:sa", "$a1:late", "$a2:pn", "$a2:pq", "$a2:cur", "$a3:cf", "$a3:cur")
            assertThat(beforeA).containsEntry("$a4:p", "-1").containsEntry("$a3:st", "CHEAT_FAILED")

            fx.flushAll()
            assertThat(fx.roster(p.a)).isEmpty()
            val from = gameClock.now().toEpochMilli()
            rosterService.findOrCreate(p.a.gid, D)
            rosterService.findOrCreate(p.b.gid, D)
            val to = gameClock.now().toEpochMilli()

            assertThat(fx.roster(p.a)).isEqualTo(rosterA)
            assertThat(fx.roster(p.b)).isEqualTo(rosterB)
            fx.assertRestored(beforeA, fx.group(p.a), from, to)
            fx.assertRestored(beforeB, fx.group(p.b), from, to)
            assertThat(fx.isDayMember(p.a)).isTrue()
            assertThat(fx.isDayMember(p.b)).isTrue()
            assertThat(fx.events(p.a)).isEmpty()
            assertThat(fx.events(p.b)).isEmpty()
        }

        @Test
        fun `O20 재적재 뒤 같은 신호는 멱등 · 넘긴 문제는 막히고 · 다음 번호는 반영된다`(): Unit = runBlocking {
            val p = play()
            val (a1, a2, _, a4) = p.a.uids
            fx.flushAll()
            rosterService.findOrCreate(p.a.gid, D)
            val v0 = fx.group(p.a).getValue("version").toLong()

            val again = solve(p.a, a1, 2, p.q2[0])
            assertThat(again.outcome).isEqualTo(SignalOutcome.REPLAY)
            assertThat(again.progress).isEqualTo(2)
            val pass = signals.pass(uid(a2), p.a.gid, PassSignal(1, QuestionId(p.q1[0].pk)))
            assertThat(pass.outcome).isEqualTo(SignalOutcome.REPLAY)
            assertThat(pass.passedAtQuestionNo).isEqualTo(1)
            assertThat(shown(p.a, a2, 1, p.q1[0])).isEqualTo(ShownOutcome.Rejected(ErrorCode.QUESTION_MISMATCH))
            assertThat(fx.group(p.a).getValue("version").toLong()).isEqualTo(v0)
            assertThat(fx.events(p.a)).isEmpty()

            val third = solve(p.a, a1, 3, p.q3[0])
            assertThat(third.outcome).isEqualTo(SignalOutcome.APPLIED)
            assertThat(third.progress).isEqualTo(3)
            assertThat(third.missionStatus).isEqualTo(MemberStatus.SUCCEEDED)
            assertThat(third.version).isEqualTo(v0 + 1)
            assertThat(fx.events(p.a).map { it["type"] }).containsExactly("solve.applied")
            start(p.a, a4)
            assertThat(fx.group(p.a)).containsEntry("$a4:p", "0").containsEntry("$a4:st", "IN_PROGRESS")
            eventually(what = "persister 가 재적재 뒤 3번 풀이를 씀") { fx.drained(p.a) }
            assertThat(solveRows(a1)).containsExactly(1, 2, 3)
        }

        @Test
        fun `O21 같은 그룹을 여럿이 동시에 재적재해도 한 번만 선다`(): Unit = runBlocking {
            val p = play()
            val beforeA = fx.group(p.a)
            val beforeB = fx.group(p.b)
            val rosterA = fx.roster(p.a)
            fx.flushAll()
            created.clear()

            val from = gameClock.now().toEpochMilli()
            val rosters = coroutineScope {
                val requests = List(16) { async(Dispatchers.Default) { rosterService.findOrCreate(p.a.gid, D) } }
                val run = async(Dispatchers.Default) { job.run(D, startAfter = GroupId(p.a.gid.value - 1)) }
                run.await()
                requests.awaitAll()
            }
            val to = gameClock.now().toEpochMilli()

            assertThat(created[p.a.gid]?.get()).isEqualTo(1)
            assertThat(created[p.b.gid]?.get()).isEqualTo(1)
            assertThat(rosters.map { it.members }.toSet()).containsExactly(rosters[0].members)
            assertThat(fx.roster(p.a)).isEqualTo(rosterA)
            val afterA = fx.group(p.a)
            fx.assertRestored(beforeA, afterA, from, to)
            fx.assertRestored(beforeB, fx.group(p.b), from, to)
            // 다시 불러도 그대로(version 이 두 번 쓰이지 않았다)
            rosterService.findOrCreate(p.a.gid, D)
            assertThat(fx.group(p.a)).isEqualTo(afterA)
        }

        private suspend fun solveRows(u: Long): List<Int> =
            db.sql("SELECT question_no FROM solve_signals WHERE game_date = :d AND user_id = :u ORDER BY question_no")
                .bind("d", D).bind("u", u)
                .map { row -> row.get("question_no", Number::class.java)!!.toInt() }
                .all().collectList().awaitSingle()

        companion object {
            val D: LocalDate = LocalDate.now(RehydrateFixture.ZONE).plusDays(10)

            val created = ConcurrentHashMap<GroupId, AtomicInteger>()

            @JvmStatic
            @DynamicPropertySource
            fun clockOffset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { RehydrateFixture.offsetTo(D, LocalTime.of(7, 30)) }
            }
        }
    }
