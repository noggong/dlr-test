package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.persister.MalformedEventException
import com.delightroom.mission.app.worker.persister.RetryableEventException
import com.delightroom.mission.app.worker.persister.SignalEventHandlers
import com.delightroom.mission.app.worker.persister.SignalFixture
import com.delightroom.mission.app.worker.persister.SignalFixture.Member
import com.delightroom.mission.app.worker.persister.SignalGroup
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.domain.result.GroupResultState
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.GroupBoardRepository
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.ResultCache
import com.delightroom.mission.core.port.SignalEventRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.BoardService
import com.delightroom.mission.infra.redis.RedisInfraAutoConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * ORACLE deadline-result O28 · O29 · O32 · I8 — persister 처리기(`SignalEventHandlers`)가 `group.decided` · `group.ranking_final` 을
 * PostgreSQL 에 커밋한 **뒤** 결과 캐시를 날짜 비교로 갱신하는지(LLD deadline-result §3.4 · LLD platform §3).
 *
 * 처리기는 직접 부른다 — persister 소비 루프의 순서 · ACK 는 platform 테스트가 본다. 스키마는 `spring.sql.init` 이 적용하고,
 * O29 는 Lua `deadline` 을 실제로 돌려 나온 Stream 이벤트를 처리기에 넘긴 뒤 API(`BoardService.latestResult`) · Hash · DB · 캐시를 맞춘다.
 */
@SpringBootTest(
    properties = [
        "spring.sql.init.mode=always",
        "worker.persister.enabled=false",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class ResultCacheWriteTest
    @Autowired
    constructor(
        private val handlers: SignalEventHandlers,
        private val signalEvents: SignalEventRepository,
        private val resultCache: ResultCache,
        private val db: DatabaseClient,
        private val redis: ReactiveStringRedisTemplate,
        private val users: UserRepository,
        private val progresses: ProgressRepository,
        private val results: GameResultRepository,
        private val groupDays: GroupDayRepository,
        private val boards: GroupBoardRepository,
    ) {
        private val fx = SignalFixture(db, redis, bucketCount = GameProperties().bucketCount)
        private val keys = RedisKeys(GameProperties())

        private suspend fun handle(fields: Map<String, String>) = handlers.handle(StreamEvent("0-1", fields))

        private suspend fun cacheRaw(gid: Long): String? = redis.opsForValue().get(keys.result(GroupId(gid))).awaitSingleOrNull()

        /** 이 그룹의 결과 행 수(모든 날짜) — `group_results` · `group_result_members`. */
        private suspend fun rowCounts(gid: Long): Pair<Long, Long> {
            suspend fun count(table: String) = db.sql("SELECT count(*) AS n FROM $table WHERE group_id = $gid")
                .map { row -> row.get("n", Number::class.java)!!.toLong() }.one().awaitSingle()
            return count("group_results") to count("group_result_members")
        }

        /** 그 날짜 `group_result_members` 의 uid → rank. */
        private suspend fun dbRanks(gid: Long, date: LocalDate): Map<Long, Int> =
            db.sql("SELECT user_id, rank FROM group_result_members WHERE game_date = :d AND group_id = $gid")
                .bind("d", date)
                .map { row -> row.get("user_id", Number::class.java)!!.toLong() to row.get("rank", Number::class.java)!!.toInt() }
                .all().collectList().awaitSingle().toMap()

        private fun groupOn(g: SignalGroup, date: LocalDate) = g.copy(date = date)

        @Test
        fun `O28 옛 결과 재처리는 캐시를 되돌리지 않고 행도 바꾸지 않는다 · 커밋이 실패하면 캐시 그대로`(): Unit = runBlocking {
            val d1 = LocalDate.of(2030, 2, 11)
            val (d2, d3) = d1.plusDays(1) to d1.plusDays(2)
            val g1 = fx.group(2, d1)
            fx.partitions(d2)
            fx.partitions(d3)
            val (a, b) = g1.uids
            val g2 = groupOn(g1, d2)

            // 11-11: 08:00 잠정 → 08:00:03 확정(유예 풀이로 순위가 뒤집힘)
            val decidedAt = fx.nextUs()
            val provisional = listOf(Member(a, "FAILED", 1, fx.nextUs()), Member(b, "FAILED", 0))
            val final = listOf(Member(b, "FAILED", 2, fx.nextUs()), Member(a, "FAILED", 1, decidedAt - 1))
            val decided1 = fx.decided(g1, "FAILED_DEADLINE", provisional, at = decidedAt, rankingFinal = 0)
            handle(decided1)
            assertThat(resultCache.get(GroupId(g1.gid))!!.rankingFinal).isFalse()
            handle(fx.rankingFinal(g1, "FAILED_DEADLINE", decidedAt, final))
            resultCache.get(GroupId(g1.gid))!!.let {
                assertThat(it.date).isEqualTo(d1)
                assertThat(it.rankingFinal).isTrue()
                assertThat(it.ranking.map { m -> m.loginId.value }).containsExactly("ss4-$b", "ss4-$a")
                assertThat(it.decidedAt).isEqualTo(SignalFixture.usToInstant(decidedAt))
            }
            assertThat(dbRanks(g1.gid, d1)).isEqualTo(mapOf(b to 1, a to 2))

            // 11-12: 다음 게임 결과는 TTL 이 남아 있어도 덮는다
            handle(fx.decided(g2, "SUCCEEDED", listOf(Member(a, "SUCCEEDED", 3, fx.nextUs()), Member(b, "SUCCEEDED", 3, fx.nextUs()))))
            val cached = cacheRaw(g1.gid)
            assertThat(resultCache.get(GroupId(g1.gid))!!.date).isEqualTo(d2)
            val rows = rowCounts(g1.gid)
            val ranks1 = dbRanks(g1.gid, d1)

            // 11-11 group.decided 를 다시 처리(재전달) — 캐시 날짜 11-12 그대로 · 행 수 · 확정 랭킹 그대로
            handle(decided1)
            assertThat(cacheRaw(g1.gid)).isEqualTo(cached)
            assertThat(rowCounts(g1.gid)).isEqualTo(rows)
            assertThat(dbRanks(g1.gid, d1)).isEqualTo(ranks1)

            // 커밋 실패 흉내 — 랭킹 줄의 uid 가 users 에 없으면 FK 위반으로 트랜잭션이 롤백된다 → 캐시에 닿지 않는다
            val broken = fx.decided(groupOn(g1, d3), "SUCCEEDED", listOf(Member(a, "SUCCEEDED", 3, fx.nextUs()), Member(Long.MAX_VALUE, "SUCCEEDED", 3, fx.nextUs())))
            assertThatThrownBy { runBlocking { handle(broken) } }.isNotInstanceOf(MalformedEventException::class.java)
            assertThat(cacheRaw(g1.gid)).isEqualTo(cached)
            assertThat(rowCounts(g1.gid)).isEqualTo(rows)
            val brokenFinal = fx.rankingFinal(groupOn(g1, d3), "SUCCEEDED", fx.nextUs(), listOf(Member(Long.MAX_VALUE, "SUCCEEDED", 3, fx.nextUs()), Member(a, "SUCCEEDED", 3, fx.nextUs())))
            assertThatThrownBy { runBlocking { handle(brokenFinal) } }.isNotInstanceOf(MalformedEventException::class.java)
            assertThat(cacheRaw(g1.gid)).isEqualTo(cached)
            assertThat(rowCounts(g1.gid)).isEqualTo(rows)
        }

        @Test
        fun `O28 캐시 쓰기가 실패하면 — 잠정은 커밋 그대로 성공 · 확정은 다시 처리하게 예외(커밋은 남는다)`(): Unit = runBlocking {
            val g = fx.group(2, LocalDate.of(2030, 2, 21))
            val factory = LettuceConnectionFactory("127.0.0.1", 1).also {
                it.afterPropertiesSet()
                it.start()
            }
            try {
                val down = ReactiveStringRedisTemplate(factory)
                val brokenCache = RedisInfraAutoConfiguration().resultCache(down, RedisScripts(down), keys, GameProperties())
                val withBrokenCache = SignalEventHandlers(signalEvents, brokenCache)
                val decidedAt = fx.nextUs()
                val members = g.uids.map { Member(it, "FAILED", 1, fx.nextUs()) }
                withBrokenCache.handle(StreamEvent("0-1", fx.decided(g, "FAILED_DEADLINE", members, at = decidedAt, rankingFinal = 0)))
                assertThat(fx.rows("group_results", g)).hasSize(1)

                // 확정 랭킹을 쓰지도(잠정 값을 지우지도) 못하면 ACK 하지 않게 예외 — PostgreSQL 커밋은 이미 됐고, 재처리는 멱등
                val final = fx.rankingFinal(g, "FAILED_DEADLINE", decidedAt, members.reversed())
                assertThatThrownBy { runBlocking { withBrokenCache.handle(StreamEvent("0-2", final)) } }
                    .isInstanceOf(RetryableEventException::class.java) // dead-letter 횟수에 세지 않는다(prd D20)
                assertThat(dbRanks(g.gid, g.date)).isEqualTo(mapOf(g.uids[1] to 1, g.uids[0] to 2))
                assertThat(cacheRaw(g.gid)).isNull()
                // Redis 가 돌아와 다시 처리하면 확정 값이 캐시에 선다
                handle(final)
                assertThat(resultCache.get(GroupId(g.gid))!!.rankingFinal).isTrue()
            } finally {
                factory.destroy()
            }
        }

        @Test
        fun `ranking 의 캐시 모양(로그인 아이디)을 읽지 못해도 정본은 기록하고 캐시만 건너뛴다`(): Unit = runBlocking {
            val g = fx.group(2, LocalDate.of(2030, 2, 23))
            val decided = fx.decided(g, "SUCCEEDED", g.uids.map { Member(it, "SUCCEEDED", 3, fx.nextUs()) })
            val noLogin = decided + ("ranking" to decided.getValue("ranking").replace(Regex("\"u\":\"[^\"]*\","), ""))
            handle(noLogin)
            assertThat(fx.rows("group_results", g)).hasSize(1)
            assertThat(fx.rows("group_result_members", g)).hasSize(2)
            assertThat(cacheRaw(g.gid)).isNull()
        }

        @Test
        fun `O28 ranking_final 이 먼저 와도(앞 group_decided 를 건너뜀) 결과 1행 · 확정 랭킹 · 뒤에 온 decided 는 덮지 않는다`(): Unit = runBlocking {
            val g = fx.group(2, LocalDate.of(2030, 2, 22))
            val (a, b) = g.uids
            val decidedAt = fx.nextUs()
            val final = fx.rankingFinal(g, "FAILED_DEADLINE", decidedAt, listOf(Member(b, "FAILED", 2, fx.nextUs()), Member(a, "FAILED", 1, decidedAt - 5)))
            handle(final)
            val result = fx.rows("group_results", g).single()
            assertThat(result["result"]).isEqualTo("FAILED_DEADLINE")
            assertThat((result["member_count"] as Number).toInt()).isEqualTo(2)
            handle(fx.decided(g, "FAILED_DEADLINE", listOf(Member(a, "FAILED", 1, decidedAt - 5), Member(b, "FAILED", 1, decidedAt - 4)), at = decidedAt, rankingFinal = 0))
            assertThat(dbRanks(g.gid, g.date)).isEqualTo(mapOf(b to 1, a to 2))
            assertThat(fx.rows("group_results", g)).containsExactly(result)
            assertThat(resultCache.get(GroupId(g.gid))!!.rankingFinal).isTrue()
            // 확정 랭킹을 같은 값으로 다시 처리해도 행이 같다(멱등)
            val members = fx.rows("group_result_members", g)
            handle(final)
            assertThat(fx.rows("group_result_members", g)).isEqualTo(members)
        }

        @Test
        fun `O29 · O32 1차 → 유예 풀이 → 2차 → persister — API · Hash · DB · 캐시 rank 가 같고 마감 실패 그룹원은 daily_progress FAILED`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val g = fx.group(6, f.date)
            val (s1, s2, late, early, absent) = g.uids
            val cheat = g.uids[5]
            val lid = fx.loginIds(g)
            val gid = GroupId(g.gid)
            // 정본(daily_progress)도 08:00 전 상태로
            fx.setStatus(g, s1, "SUCCEEDED", 3)
            fx.setStatus(g, s2, "SUCCEEDED", 3)
            fx.setStatus(g, late, "IN_PROGRESS", 1)
            fx.setStatus(g, early, "IN_PROGRESS", 1)
            fx.setStatus(g, absent, "NOT_STARTED", -1)
            fx.setStatus(g, cheat, "CHEAT_FAILED", 1)
            val t720 = f.us(LocalTime.of(7, 20))
            f.write(
                mapOf(
                    gid to listOf(
                        DeadlineFixture.M(s1, lid.getValue(s1), "SUCCEEDED", 3, ls = t720),
                        DeadlineFixture.M(s2, lid.getValue(s2), "SUCCEEDED", 3, ls = t720),
                        DeadlineFixture.M(late, lid.getValue(late), "IN_PROGRESS", 1, cur = "q2", ls = f.us(LocalTime.of(7, 1))),
                        DeadlineFixture.M(early, lid.getValue(early), "IN_PROGRESS", 1, cur = "q2", ls = f.us(LocalTime.of(7, 0, 30))),
                        DeadlineFixture.M(absent, lid.getValue(absent), "NOT_STARTED", -1),
                        DeadlineFixture.M(cheat, lid.getValue(cheat), "CHEAT_FAILED", 1, ls = f.us(LocalTime.of(7, 5))),
                    ),
                ),
            )

            // 1차(08:00:00.5) — late 는 3번 진입이 아니므로 미루지 않고 확정 · early 가 위
            assertThat(f.repository(f.at(Duration.ofMillis(500))).runDeadline(gid, f.date, DeadlinePhase.FIRST).code)
                .isEqualTo(DeadlineCode.DECIDED)
            // 유예 풀이(08:00:01.5 도착 · 07:59:59.9 클릭) — solve-signal 의 Lua solve 그대로: late 진행도 2
            assertThat(f.solve(f.at(Duration.ofMillis(1_500)), gid, late, 2, "q2", f.us(LocalTime.of(7, 59, 59, 900_000_000))))
                .isEqualTo("OK")
            // 2차(08:00:03.5) — 랭킹 확정
            assertThat(f.repository(f.at(Duration.ofMillis(3_500))).runDeadline(gid, f.date, DeadlinePhase.SECOND).code)
                .isEqualTo(DeadlineCode.FINALIZED)

            // 그룹 결과 이벤트만 처리기로(solve.applied 는 문제 PK 가 시드에 없는 시험 값이라 solve-signal 테스트가 본다)
            val events = f.events().getValue(g.gid).filter { it["type"]!!.startsWith("group.") }
            assertThat(events.map { it["type"] }).containsExactly("group.decided", "group.ranking_final")
            events.forEach { handle(it) }
            val progressRows = fx.rows("daily_progress", g)
            val memberRows = fx.rows("group_result_members", g)

            // 1차 랭킹과 확정 랭킹이 실제로 다르다(유예 반영) — 그래야 이 판정이 덮어쓰기를 본다
            val provisional = GroupResultState.rankingJson(events[0].getValue("ranking")).associate { it.loginId.value to it.rank }
            val hashRanks = GroupResultState.rankingJson(f.hash(gid).getValue("ranking")).associate { it.loginId.value to it.rank }
            assertThat(provisional.getValue(lid.getValue(late))).isEqualTo(4)
            assertThat(hashRanks.getValue(lid.getValue(late))).isEqualTo(3)
            assertThat(hashRanks).hasSize(6)

            val dbRank = dbRanks(g.gid, f.date).mapKeys { lid.getValue(it.key) }
            val cached = resultCache.get(gid)!!
            assertThat(cached.date).isEqualTo(f.date)
            assertThat(cached.rankingFinal).isTrue()
            val cacheRanks = cached.ranking.associate { it.loginId.value to it.rank }

            // API — 그날 08:00:05(오늘 Hash 경로)와 다음 날 00:30(Hash 없음 → 캐시 경로) 두 번
            val game = GameProperties()
            for (at in listOf(game.deadlineAt(f.date).plusSeconds(5), f.date.plusDays(1).atTime(0, 30).atZone(game.zone).toInstant())) {
                val board = BoardService(users, progresses, results, groupDays, resultCache, game, Clock.fixed(at, game.zone), boards)
                for (uid in g.uids) {
                    val login = lid.getValue(uid)
                    val view = board.latestResult(LoginId(login), gid)
                    assertThat(view.result.date).describedAs("$at").isEqualTo(f.date)
                    assertThat(view.result.rankingFinal).isTrue()
                    val apiRank = view.myRank
                    assertThat(listOf(apiRank, hashRanks[login], dbRank[login], cacheRanks[login]))
                        .describedAs("$login @ $at — API · Hash · DB · 캐시")
                        .containsOnly(hashRanks.getValue(login))
                }
            }

            // O32 — 마감 실패 그룹원(랭킹 s = FAILED)은 daily_progress 도 FAILED, 완료 · 부정 실패 그룹원은 그대로
            val status = progressRows.associate { (it["user_id"] as Number).toLong() to it["status"] }
            assertThat(status).isEqualTo(
                mapOf(s1 to "SUCCEEDED", s2 to "SUCCEEDED", late to "FAILED", early to "FAILED", absent to "FAILED", cheat to "CHEAT_FAILED"),
            )
            // 두 이벤트를 다시 처리해도 행이 같다(updated_at 포함)
            events.forEach { handle(it) }
            assertThat(fx.rows("daily_progress", g)).isEqualTo(progressRows)
            assertThat(fx.rows("group_result_members", g)).isEqualTo(memberRows)
        }

        @Test
        fun `I8 이벤트 1,000건을 무작위 순서 · 중복으로 — 캐시 날짜는 줄지 않고 끝 값은 가장 늦은 날의 확정 결과`(): Unit = runBlocking {
            val dates = (0L until 5L).map { LocalDate.of(2030, 3, 1).plusDays(it) }
            val g = fx.group(3, dates[0])
            dates.forEach { fx.partitions(it) }
            val (a, b, c) = g.uids
            val random = Random(20261111)
            val finals = mutableMapOf<LocalDate, List<Member>>()
            val distinct = dates.flatMap { d ->
                val gd = groupOn(g, d)
                val decidedAt = fx.nextUs()
                val provisional = listOf(a, b, c).shuffled(random).map { Member(it, "FAILED", 1, fx.nextUs()) }
                val final = listOf(a, b, c).shuffled(random).map { Member(it, "FAILED", 1, fx.nextUs()) }
                finals[d] = final
                listOf(
                    fx.decided(gd, "FAILED_DEADLINE", provisional, at = decidedAt, rankingFinal = 0),
                    fx.rankingFinal(gd, "FAILED_DEADLINE", decidedAt, final),
                )
            }
            val events = (distinct + List(1_000 - distinct.size) { distinct[random.nextInt(distinct.size)] }).shuffled(random)

            var seen: LocalDate? = null
            for ((i, e) in events.withIndex()) {
                handle(e)
                val date = resultCache.get(GroupId(g.gid))!!.date
                seen?.let { assertThat(date).describedAs("이벤트 $i 뒤 캐시 날짜").isAfterOrEqualTo(it) }
                seen = date
            }

            val last = dates.last()
            val cached = resultCache.get(GroupId(g.gid))!!
            assertThat(cached.date).isEqualTo(last)
            assertThat(cached.rankingFinal).isTrue()
            assertThat(cached.ranking.map { it.loginId.value }).isEqualTo(finals.getValue(last).map { "ss4-${it.uid}" })
            // 정본도 날짜마다 확정 랭킹 · 결과 1행
            for (d in dates) {
                assertThat(dbRanks(g.gid, d)).describedAs("$d").isEqualTo(finals.getValue(d).withIndex().associate { (i, m) -> m.uid to i + 1 })
            }
            assertThat(rowCounts(g.gid)).isEqualTo(dates.size.toLong() to dates.size * 3L)
        }
    }
