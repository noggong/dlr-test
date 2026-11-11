package com.delightroom.mission.app.worker.rehydrate

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.app.worker.member.MemberProperties
import com.delightroom.mission.app.worker.persister.StreamPersister
import com.delightroom.mission.app.worker.persister.eventually
import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.progress.PassSignal
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.progress.SignalOutcome
import com.delightroom.mission.core.domain.progress.SignalResult
import com.delightroom.mission.core.domain.progress.SolveSignal
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.RosterService
import com.delightroom.mission.core.service.SignalService
import com.delightroom.mission.infra.redis.RedisInfraAutoConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveRedisCallback
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * 재적재 대조 — 부정 즉시 실패 · 유예 풀이 · 08:00 ~ 08:00:03 창(ORACLE platform O27 ~ O29 — LLD platform §4.2).
 *
 * 게임은 컨텍스트 시각 `D 07:30`(오프셋)에서 Lua 로 진행하고, 스프링이 띄운 persister 가 PostgreSQL 에 쓴 뒤 `FLUSHALL` 한다.
 * 08:00 뒤의 단계는 [at] 이 Redis `TIME` 에 맞춰 그때그때 잡은 오프셋으로 저장소 · 서비스 · [MemberJob] 을 따로 만든다 —
 * 유예(08:00:03)가 3초라 컨텍스트 하나의 고정 오프셋으로는 창 안에 닿지 못한다(deadline 테스트의 `DeadlineFixture.at` 과 같은 방식).
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
@DirtiesContext
class RehydrateGraceTest
    @Autowired
    constructor(
        private val rosterService: RosterService,
        private val signals: SignalService,
        private val progress: ProgressRepository,
        private val persister: StreamPersister,
        private val gameClock: GameClock,
        private val game: GameProperties,
        private val keys: RedisKeys,
        private val scripts: RedisScripts,
        private val users: UserRepository,
        private val groups: GroupRepository,
        private val dailyProgress: DailyProgressStore,
        private val redis: ReactiveStringRedisTemplate,
        db: DatabaseClient,
    ) {
        private val fx = RehydrateFixture(db, redis, keys, D)

        @BeforeEach
        fun running() {
            assertThat(persister.isRunning).isTrue()
        }

        /** 한 시점에 고정한 저장소 · 서비스 묶음 — Lua `t` 와 앱 시각이 둘 다 [since] 를 지난 08:00 에서 흐른다. */
        private inner class At(props: GameProperties, val clock: GameClock) {
            private val infra = RedisInfraAutoConfiguration()
            val progress: ProgressRepository = infra.progressRepository(redis, scripts, keys, clock, props)
            val rosters = infra.rosterRepository(redis, scripts, keys, clock)
            val groupDays: GroupDayRepository = infra.groupDayRepository(redis, scripts, keys, props)
            val roster = RosterService(rosters, users, groups, dailyProgress, clock)
            val signals = SignalService(progress, roster, clock, props)
            val job = MemberJob(groups, users, rosters, dailyProgress, groupDays, clock, MemberProperties(enabled = false))
        }

        /** Lua 의 `t` 와 앱 게임 시각이 지금 `D 08:00:00 + since` 가 되는 묶음. */
        private suspend fun at(since: Duration): At {
            val redisUs = redisNowUs()
            val target = game.deadlineAt(D).plus(since)
            val offset = Duration.ofNanos((GameClock.epochMicros(target) - redisUs) * 1_000L)
            val props = game.copy(clockOffset = offset)
            // 앱 시각도 Redis TIME 기준으로 — 호스트 · 컨테이너 시계 차이가 경계(08:00 · 08:00:03)를 흐리지 않게
            val host = Clock.systemUTC()
            val redisClock = Clock.offset(host, Duration.ofNanos((redisUs - GameClock.epochMicros(host.instant())) * 1_000L))
            return At(props, GameClock(props, redisClock))
        }

        private suspend fun redisNowUs(): Long =
            redis.execute(ReactiveRedisCallback { it.serverCommands().time(TimeUnit.MICROSECONDS) }).awaitSingle()

        private fun uid(v: Long) = UserId(v)

        private fun us(time: String): Long = GameClock.epochMicros(OffsetDateTime.parse("${D}T$time+09:00").toInstant())

        private suspend fun start(g: TestGroup, u: Long) {
            assertThat(progress.startMission(uid(u), g.gid, D)).describedAs("start $u").isInstanceOf(MissionStartOutcome.Started::class.java)
        }

        private suspend fun shown(g: TestGroup, u: Long, no: Int, q: Q): ShownOutcome =
            progress.markShown(uid(u), g.gid, D, no, QuestionId(q.pk), q.id)

        private suspend fun solve(g: TestGroup, u: Long, no: Int, q: Q, service: SignalService = signals, clickedAt: Instant = gameClock.now()) =
            service.solve(uid(u), g.gid, SolveSignal(no, QuestionId(q.pk), "${D}T07:29:59+09:00", clickedAt))

        /** 1 ~ [upTo] 번을 풀고, [enterNext] 면 다음 번호에 진입한다(지금 문제를 남긴다). */
        private suspend fun play(g: TestGroup, u: Long, qs: List<List<Q>>, upTo: Int, enterNext: Boolean = false) {
            start(g, u)
            for (no in 1..upTo) {
                shown(g, u, no, qs[no - 1][0])
                assertThat(solve(g, u, no, qs[no - 1][0]).outcome).isEqualTo(SignalOutcome.APPLIED)
            }
            if (enterNext) assertThat(shown(g, u, upTo + 1, qs[upTo][0])).isInstanceOf(ShownOutcome.Shown::class.java)
        }

        private suspend fun questions(): List<List<Q>> = listOf(fx.questions(1), fx.questions(2), fx.questions(3))

        /** 유예 안(클릭 08:00 전)의 3번 풀이. */
        private suspend fun graceSolve(w: At, g: TestGroup, u: Long, q: Q): SignalResult =
            solve(g, u, 3, q, w.signals, OffsetDateTime.parse("${D}T07:59:59.900+09:00").toInstant())

        @Test
        fun `O27 부정 실패로 즉시 실패한 그룹을 FLUSHALL 뒤 재적재 — 칸이 FLUSH 전과 같고 같은 신호는 멱등`(): Unit = runBlocking {
            val qs = questions()
            val g = fx.group(3)
            val (f1, f2, f3) = g.uids
            rosterService.findOrCreate(g.gid, D)
            // f1 · f3 는 3번까지(성공) · f2 는 1번 진입 → 패스 → 교체 문제 풀이 → 2번 진입 → 부정 실패(마지막 — fail Lua 가 결과를 낸다)
            play(g, f1, qs, 3)
            play(g, f3, qs, 3)
            start(g, f2)
            shown(g, f2, 1, qs[0][0])
            assertThat(signals.pass(uid(f2), g.gid, PassSignal(1, QuestionId(qs[0][0].pk))).outcome).isEqualTo(SignalOutcome.APPLIED)
            assertThat(shown(g, f2, 1, qs[0][1])).isInstanceOf(ShownOutcome.Shown::class.java)
            assertThat(solve(g, f2, 1, qs[0][1]).outcome).isEqualTo(SignalOutcome.APPLIED)
            assertThat(shown(g, f2, 2, qs[1][0])).isInstanceOf(ShownOutcome.Shown::class.java)
            val failed = signals.failByCheat(uid(f2), g.gid)
            assertThat(failed.groupStatus).isEqualTo(GroupStatus.FAILED)
            val before = fx.group(g)
            assertThat(before).containsEntry("result", "FAILED_IMMEDIATE").containsEntry("rankingFinal", "1")
                .containsEntry("$f2:st", "CHEAT_FAILED").containsKeys("$f2:cf", "$f2:pn", "$f2:pq", "$f2:cur", "ranking", "decidedAt")
            val rosterBefore = fx.roster(g)
            eventually(what = "persister 가 F 를 끝까지 씀") { fx.drained(g) }

            fx.flushAll()
            val from = gameClock.now().toEpochMilli()
            rosterService.findOrCreate(g.gid, D)
            val to = gameClock.now().toEpochMilli()

            assertThat(fx.roster(g)).isEqualTo(rosterBefore)
            fx.assertRestored(before, fx.group(g), from, to)
            assertThat(fx.events(g)).isEmpty()
            // 재적재 뒤 같은 신호 — 바뀜 없음
            val v = fx.group(g).getValue("version")
            val again = signals.failByCheat(uid(f2), g.gid)
            assertThat(again.outcome).isEqualTo(SignalOutcome.REPLAY)
            assertThat(again.missionStatus).isEqualTo(MemberStatus.CHEAT_FAILED)
            assertThat(again.groupStatus).isEqualTo(GroupStatus.FAILED)
            assertThat(solve(g, f3, 3, qs[2][0]).outcome).isEqualTo(SignalOutcome.REPLAY)
            assertThat(fx.group(g).getValue("version")).isEqualTo(v)
            assertThat(fx.events(g)).isEmpty()
        }

        @Test
        fun `O28 유예 풀이가 있는 날을 재적재 — 결과 · 랭킹이 같고 rankingFinal 은 0 으로 섰다가 마감 따라잡기로 1`(): Unit = runBlocking {
            val qs = questions()
            // A(2명): a1 성공 · a2 3번 진입 → 유예 풀이로 전원 성공(결과 SUCCEEDED · 유예 안 확정)
            // B(3명): b1 성공 · b2 3번 진입 → 유예 풀이 · b3 1번만 → 2차가 FAILED_DEADLINE 으로 확정
            val a = fx.group(2)
            val b = fx.group(3)
            // persister 는 ACK 한 메시지를 트림하고 버킷마다 마지막 메시지만 남긴다 — 아래 Stream 검사는 A · B 가 다른 버킷일 때 뜻이 있다
            assertThat(keys.bucket(a.gid)).isNotEqualTo(keys.bucket(b.gid))
            rosterService.findOrCreate(a.gid, D)
            rosterService.findOrCreate(b.gid, D)
            play(a, a.uids[0], qs, 3)
            play(a, a.uids[1], qs, 2, enterNext = true)
            play(b, b.uids[0], qs, 3)
            play(b, b.uids[1], qs, 2, enterNext = true)
            play(b, b.uids[2], qs, 1)

            val first = at(Duration.ofMillis(100))
            assertThat(first.groupDays.runDeadline(a.gid, D, DeadlinePhase.FIRST).code).isEqualTo(DeadlineCode.PENDING)
            assertThat(first.groupDays.runDeadline(b.gid, D, DeadlinePhase.FIRST).code).isEqualTo(DeadlineCode.PENDING)
            val ga = graceSolve(first, a, a.uids[1], qs[2][0])
            assertThat(ga.outcome).isEqualTo(SignalOutcome.APPLIED)
            assertThat(ga.groupStatus).isEqualTo(GroupStatus.SUCCEEDED)
            val gb = graceSolve(first, b, b.uids[1], qs[2][0])
            assertThat(gb.outcome).isEqualTo(SignalOutcome.APPLIED)
            assertThat(gb.missionStatus).isEqualTo(MemberStatus.SUCCEEDED)
            assertThat(fx.group(a)).containsEntry("result", "SUCCEEDED").containsEntry("rankingFinal", "0")
            assertThat(fx.group(a).getValue("${a.uids[1]}:ls").toLong()).isBetween(us("08:00:00"), us("08:00:03") - 1)

            val second = at(Duration.ofMillis(3_200))
            assertThat(second.groupDays.runDeadline(a.gid, D, DeadlinePhase.SECOND).code).isEqualTo(DeadlineCode.FINALIZED)
            assertThat(second.groupDays.runDeadline(b.gid, D, DeadlinePhase.SECOND).code).isEqualTo(DeadlineCode.DECIDED_FINAL)
            val beforeA = fx.group(a)
            val beforeB = fx.group(b)
            assertThat(beforeA).containsEntry("rankingFinal", "1")
            assertThat(beforeB).containsEntry("result", "FAILED_DEADLINE").containsEntry("rankingFinal", "1")
                .containsEntry("${b.uids[1]}:st", "SUCCEEDED").containsEntry("${b.uids[1]}:p", "3")
                .containsEntry("${b.uids[2]}:st", "FAILED")
            eventually(what = "persister 가 A · B 를 끝까지 씀") { fx.drained(a) && fx.drained(b) }

            fx.flushAll()
            val w = at(Duration.ofSeconds(10))
            // 재적재만(앞 실행이 명단을 세우고 따라잡기 전에 끊긴 것처럼) — PostgreSQL 에는 랭킹 확정 표시가 없어 rankingFinal 0
            val from = w.clock.now().toEpochMilli()
            w.roster.findOrCreate(a.gid, D)
            w.roster.findOrCreate(b.gid, D)
            val restoredA = fx.group(a)
            val restoredB = fx.group(b)
            for ((before, after) in listOf(beforeA to restoredA, beforeB to restoredB)) {
                assertThat(after).containsEntry("rankingFinal", "0")
                fx.assertRestored(before.minus("rankingFinal"), after.minus("rankingFinal"), from, w.clock.now().toEpochMilli())
            }
            // 감시가 다시 돈 run — 마감 따라잡기가 랭킹을 확정한다
            val run = w.job.run(D, startAfter = GroupId(a.gid.value - 1))
            assertThat(run.caughtUp).isEqualTo(2)
            assertThat(run.complete).isTrue()
            val to = w.clock.now().toEpochMilli()
            fx.assertRestored(beforeA, fx.group(a), from, to)
            fx.assertRestored(beforeB, fx.group(b), from, to)
            assertThat(fx.events(a).map { it["type"] }).containsExactly("group.ranking_final")
            assertThat(fx.events(b).map { it["type"] }).containsExactly("group.ranking_final")
        }

        @Test
        fun `O29 08시 ~ 08시 0분 3초 사이 Redis 가 비면 유예 신호는 MISSION_NOT_STARTED · worker 가 올리면 미룸이 다시 서고 유예 풀이를 받는다`(): Unit =
            runBlocking {
                val qs = questions()
                val c = fx.group(2)
                val (c1, c2) = c.uids
                rosterService.findOrCreate(c.gid, D)
                play(c, c1, qs, 3)
                play(c, c2, qs, 2, enterNext = true)
                val deadline = at(Duration.ofMillis(50))
                assertThat(deadline.groupDays.runDeadline(c.gid, D, DeadlinePhase.FIRST).code).isEqualTo(DeadlineCode.PENDING)
                assertThat(fx.group(c)).containsEntry("pending", "1")
                eventually(what = "persister 가 C 를 끝까지 씀") { fx.drained(c) }
                fx.flushAll()

                val w = at(Duration.ofMillis(100))
                // api 경로 — 08:00 뒤에는 명단을 만들지 않는다
                val refused = runCatching { graceSolve(w, c, c2, qs[2][0]) }.exceptionOrNull()
                assertThat(refused).isInstanceOf(BusinessException::class.java)
                assertThat((refused as BusinessException).errorCode).isEqualTo(ErrorCode.MISSION_NOT_STARTED)
                assertThat(fx.roster(c)).isEmpty()
                assertThat(fx.group(c)).isEmpty()
                assertThat(fx.events(c)).isEmpty()
                // 패스 · 부정 실패도 08:00 뒤에는 명단을 만들지 않는다
                for (refusedSignal in listOf<suspend () -> SignalResult>(
                    { w.signals.pass(uid(c2), c.gid, PassSignal(3, QuestionId(qs[2][0].pk))) },
                    { w.signals.failByCheat(uid(c2), c.gid) },
                )) {
                    val e = runCatching { refusedSignal() }.exceptionOrNull()
                    assertThat(e).isInstanceOf(BusinessException::class.java)
                    assertThat((e as BusinessException).errorCode).isEqualTo(ErrorCode.MISSION_NOT_STARTED)
                }
                assertThat(fx.roster(c)).isEmpty()

                // worker 가 (감시로) 올리면 — 재적재 뒤 마감 따라잡기 1차가 같은 규칙으로 다시 미룬다
                val run = w.job.run(D, startAfter = GroupId(c.gid.value - 1))
                assertThat(run.rehydrated).isEqualTo(1)
                assertThat(run.caughtUp).isEqualTo(1)
                assertThat(fx.group(c)).containsEntry("pending", "1").doesNotContainKey("result")
                    .containsEntry("$c2:p", "2").containsEntry("$c2:cur", qs[2][0].pk)
                // 미룸은 채널로만 알린다(Stream 에 남기지 않는다 — deadline-result LLD §4.1)
                assertThat(fx.events(c)).isEmpty()
                // 창(3초)을 넉넉히 — FLUSH 뒤라 Lua 시각을 08:00:00.2 로 다시 맞춰도 해가 없다
                val again = at(Duration.ofMillis(200))
                val accepted = graceSolve(again, c, c2, qs[2][0])
                assertThat(again.clock.now()).isBefore(game.graceEndAt(D))
                assertThat(accepted.outcome).isEqualTo(SignalOutcome.APPLIED)
                assertThat(accepted.groupStatus).isEqualTo(GroupStatus.SUCCEEDED)
                val h = fx.group(c)
                assertThat(h).containsEntry("result", "SUCCEEDED").containsEntry("rankingFinal", "0").doesNotContainKey("pending")
                assertThat(h.getValue("decidedAt").toLong()).isBetween(us("08:00:00"), us("08:00:03") - 1)
            }

        companion object {
            val D: LocalDate = LocalDate.now(RehydrateFixture.ZONE).plusDays(20)

            @JvmStatic
            @DynamicPropertySource
            fun clockOffset(registry: DynamicPropertyRegistry) {
                registry.add("game.clock-offset") { RehydrateFixture.offsetTo(D, LocalTime.of(7, 30)) }
            }
        }
    }
