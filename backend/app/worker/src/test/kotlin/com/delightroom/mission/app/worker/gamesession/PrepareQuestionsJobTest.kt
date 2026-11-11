package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.prepare.PrepareQuestionsJob
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.core.port.UserRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ContextConfiguration
import io.r2dbc.spi.ConnectionFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * `worker: prepare-questions`(ORACLE game-session O29 · O37 · I5 — LLD §3.6). 잡 본문 [PrepareQuestionsJob.run] 을 06:30 · 06:50 순번으로
 * 직접 부른다. `D` 는 KST 내일이다 — `PXAT D 08:00` 이 지금보다 뒤여야 키가 바로 사라지지 않는다.
 */
// 묶음 2명 — 여러 페이지(Q5 의 id > after)를 넘어가는 길을 탄다
@SpringBootTest(
    properties = [
        "worker.persister.enabled=false",
        "worker.prepare-questions.enabled=false",
        "worker.prepare-questions.batch-size=2",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class PrepareQuestionsJobTest
    @Autowired
    constructor(
        private val job: PrepareQuestionsJob,
        private val cache: QuestionBankCache,
        private val users: UserRepository,
        private val gameClock: GameClock,
        private val redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
        private val connectionFactory: ConnectionFactory,
    ) {
        private val kst = ZoneId.of("Asia/Seoul")
        private val d: LocalDate = LocalDate.now(kst).plusDays(1)

        @BeforeEach
        fun schema(): Unit = runBlocking {
            // worker 는 스키마를 적용하지 않는다 — api 와 같은 schema.sql · data.sql(문제 120개 × 3)을 테스트가 적용한다
            ResourceDatabasePopulator(ClassPathResource("schema.sql"), ClassPathResource("data.sql"))
                .populate(connectionFactory).awaitSingleOrNull()
        }

        @Test
        fun `O29 06시30분 실행은 그때까지 가입자만 06시50분 실행은 그 뒤 가입자를 더하고 NX 고갈 만료를 지킨다`(): Unit = runBlocking {
            val g = group(1)
            val solo = group(2)
            val early = user(g, at(6, 0))
            val soloUser = user(solo, at(6, 0))
            val preset = user(g, at(6, 0))
            val partial = user(g, at(6, 0))
            val exhausted = user(g, at(6, 0))
            val mid = user(g, at(6, 40))
            val late = user(g, at(6, 55))
            // 구간 경계: [06:30, 06:50) — 06:30:00 은 06:50 실행에, 06:50:00 은 어느 실행에도 없다
            val atFirst = user(g, at(6, 30))
            val atSecond = user(g, at(6, 50))
            serve(partial, 1, 110)
            serve(exhausted, 2, 120)
            val presetBank = QuestionBank(Difficulty.entries.associateWith { listOf(firstId(it)) })
            assertThat(cache.putIfAbsent(preset, d, presetBank, gameClock.deadlineAt(d))).isTrue()
            val presetRaw = raw(preset)

            val first = job.run(d, 0)

            for (u in listOf(early, soloUser, preset, partial)) assertThat(raw(u)).describedAs("06:00 유저 $u").isNotNull()
            for (u in listOf(mid, late, exhausted, atFirst, atSecond)) assertThat(raw(u)).describedAs("키 없어야 함 $u").isNull()
            assertThat(raw(preset)).isEqualTo(presetRaw)
            assertThat(first.failedBatches).isZero()
            assertThat(first.exhausted).isGreaterThanOrEqualTo(1)
            assertThat(first.existing).isGreaterThanOrEqualTo(1)
            for (u in listOf(early, soloUser)) {
                val bank = cache.get(u, d)!!
                Difficulty.entries.forEach { assertThat(bank.forDifficulty(it)).hasSize(20).doesNotHaveDuplicates() }
            }
            // 난이도 1 미출제가 10개뿐 — 있는 만큼 전부
            assertThat(cache.get(partial, d)!!.forDifficulty(Difficulty.ONE)).hasSize(10)
            val earlyRaw = raw(early)

            job.run(d, 1)

            for (u in listOf(mid, atFirst)) assertThat(raw(u)).describedAs("06:30 ~ 06:50 유저 $u").isNotNull()
            for (u in listOf(late, exhausted, atSecond)) assertThat(raw(u)).describedAs("키 없어야 함 $u").isNull()
            assertThat(raw(preset)).isEqualTo(presetRaw)
            assertThat(raw(early)).describedAs("06:50 실행은 06:30 이전 가입자를 건드리지 않는다").isEqualTo(earlyRaw)
            val deadlineMs = OffsetDateTime.of(d, LocalTime.of(8, 0), kst.rules.getOffset(d.atTime(8, 0))).toInstant().toEpochMilli()
            for (u in listOf(early, soloUser, preset, partial, mid, atFirst)) {
                // 남은 ms 로 PX 를 거니(LLD v6 §2.1) 앱 · Redis 시계 차이만큼 어긋날 수 있다 — ±1초
                assertThat(pexpiretime(u)).describedAs("PEXPIRETIME $u").isBetween(deadlineMs - 1_000, deadlineMs + 1_000)
            }
        }

        @Test
        fun `O37 06시30분 06시50분 실행 뒤 그날 그룹원 전원의 은행이 있고 06시50분 가입자는 그룹원도 은행도 없다`(): Unit = runBlocking {
            val g = group(5)
            val early = user(g, at(6, 0))
            val mid = user(g, at(6, 40))
            val edge = user(g, d.atTime(6, 49, 59, 999_999_000).atZone(kst).toOffsetDateTime())
            val cut = user(g, at(6, 50))

            job.run(d, 0)
            job.run(d, 1)

            // 명단을 만드는 세 길이 쓰는 Q4 와 같은 cutoff(prd D24) — 그날 그룹원 = 은행이 미리 있는 유저
            val members = users.findMembersJoinedBefore(listOf(GroupId(g)), gameClock.joinCutoffAt(d))[GroupId(g)].orEmpty().keys
            assertThat(members).containsExactlyInAnyOrder(early, mid, edge)
            for (u in members) assertThat(raw(u)).describedAs("그날 그룹원 $u 의 은행").isNotNull()
            assertThat(raw(cut)).describedAs("06:50:00 가입자 — 그날 그룹원이 아니고 은행도 없다").isNull()
        }

        @Test
        fun `I5 은행에 출제 이력이 없다`(): Unit = runBlocking {
            val g = group(3)
            val users = (1..5).map { user(g, at(6, 10)) }
            users.forEachIndexed { i, u -> Difficulty.entries.forEach { serve(u, it.level, 25 * i + 10) } }
            job.run(d, 0)
            job.run(d, 1)

            val keys = redis.keys("bank:{*}:$d").collectList().awaitSingle()
            assertThat(keys).hasSizeGreaterThanOrEqualTo(users.size)
            assertThat(users.map { raw(it) }).doesNotContainNull()
            for (key in keys) {
                val uid = UserId(key.substringAfter("{").substringBefore("}").toLong())
                val bankIds = cache.get(uid, d)!!.idsByDifficulty.values.flatten().toSet()
                assertThat(bankIds intersect served(uid)).describedAs("은행 ∩ 출제 이력 $key").isEmpty()
            }
        }

        @Test
        fun `O29 (바탕) 실행 시각 고르기 — 다음 prepare 시각 · 날짜 넘김 · 늦게 끝난 실행 뒤 다음 순번`() {
            val day = LocalDate.of(2026, 11, 11)
            fun at(h: Int, m: Int, s: Int = 0, date: LocalDate = day) = date.atTime(h, m, s).atZone(kst).toInstant()
            fun run(i: Int, h: Int, m: Int, date: LocalDate = day) = PrepareQuestionsJob.Run(date, i, at(h, m, date = date))
            assertThat(job.nextRun(at(0, 0))).isEqualTo(run(0, 6, 30))
            assertThat(job.nextRun(at(6, 29, 59))).isEqualTo(run(0, 6, 30))
            assertThat(job.nextRun(at(6, 30))).isEqualTo(run(1, 6, 50))
            assertThat(job.nextRun(at(6, 50))).isEqualTo(run(0, 6, 30, day.plusDays(1)))
            // 기동 때는 지난 시각을 돌지 않는다
            assertThat(job.nextRun(at(7, 10))).isEqualTo(run(0, 6, 30, day.plusDays(1)))
            // 06:30 실행이 06:50 을 넘겨 끝나면 06:50 실행을 바로 — 그날 08:00 전까지만
            assertThat(job.nextRun(at(7, 10), last = run(0, 6, 30))).isEqualTo(run(1, 6, 50))
            assertThat(job.nextRun(at(6, 40), last = run(0, 6, 30))).isEqualTo(run(1, 6, 50))
            assertThat(job.nextRun(at(8, 0), last = run(0, 6, 30))).isEqualTo(run(0, 6, 30, day.plusDays(1)))
            assertThat(job.nextRun(at(6, 51), last = run(1, 6, 50))).isEqualTo(run(0, 6, 30, day.plusDays(1)))
        }

        @Test
        fun `O29 (바탕) 은행 캐시 — 값 왕복 · 있으면 두지 않음 · 파이프라인 수`(): Unit = runBlocking {
            val g = group(4)
            val a = user(g, at(6, 0))
            val b = user(g, at(6, 0))
            val bankA = QuestionBank(mapOf(Difficulty.ONE to listOf(3L, 1L, 2L), Difficulty.TWO to listOf(130L), Difficulty.THREE to listOf(250L, 241L)))
            val bankB = QuestionBank(Difficulty.entries.associateWith { listOf(firstId(it)) })
            val expireAt = gameClock.deadlineAt(d)
            assertThat(cache.putIfAbsent(a, d, bankA, expireAt)).isTrue()
            assertThat(raw(a)).isEqualTo("""{"1":[3,1,2],"2":[130],"3":[250,241]}""")
            assertThat(cache.get(a, d)).isEqualTo(bankA)
            assertThat(cache.putIfAbsent(a, d, bankB, expireAt)).isFalse()
            assertThat(cache.get(a, d)).isEqualTo(bankA)
            assertThat(cache.putAllIfAbsent(d, mapOf(a to bankB, b to bankB), expireAt)).isEqualTo(1)
            assertThat(cache.get(b, d)).isEqualTo(bankB)
            assertThat(cache.get(UserId(Long.MAX_VALUE), d)).isNull()
        }

        // ---- 고정 데이터 ----

        private fun at(h: Int, m: Int): OffsetDateTime = d.atTime(h, m).atZone(kst).toOffsetDateTime()

        private suspend fun group(tag: Int): Long {
            val name = "p29-$tag-${seq.incrementAndGet()}-${System.nanoTime() % 100_000}"
            return db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, :n, 1) RETURNING id")
                .bind("n", name)
                .map { row -> row.get("id", Number::class.java)!!.toLong() }
                .awaitOne()
        }

        private suspend fun user(groupId: Long, joinedAt: OffsetDateTime): UserId {
            val login = "p29u${seq.incrementAndGet()}x${System.nanoTime() % 1_000_000}"
            return db.sql(
                "INSERT INTO users (login_id, password_hash, group_id, joined_at) VALUES (:l, 'x', :g, :j) RETURNING id",
            )
                .bind("l", login)
                .bind("g", groupId)
                .bind("j", joinedAt)
                .map { row -> UserId(row.get("id", Number::class.java)!!.toLong()) }
                .awaitOne()
        }

        /** 난이도 [level] 문제를 ID 순서로 [count] 개 이 유저의 출제 이력에 넣는다. */
        private suspend fun serve(uid: UserId, level: Int, count: Int) {
            db.sql(
                "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                    "SELECT :u, id, DATE '2026-01-01', :lvl, 'SHOWN', now() FROM questions WHERE difficulty = :lvl ORDER BY id LIMIT :c",
            )
                .bind("u", uid.value)
                .bind("lvl", level)
                .bind("c", count)
                .fetch().rowsUpdated().awaitSingle()
        }

        private suspend fun served(uid: UserId): Set<Long> =
            db.sql("SELECT question_id FROM served_questions WHERE user_id = :u")
                .bind("u", uid.value)
                .map { row -> row.get("question_id", Number::class.java)!!.toLong() }
                .flow().toList().toSet()

        private suspend fun firstId(d: Difficulty): Long =
            db.sql("SELECT min(id) AS id FROM questions WHERE difficulty = :d")
                .bind("d", d.level)
                .map { row -> row.get("id", Number::class.java)!!.toLong() }
                .awaitOne()

        private suspend fun raw(uid: UserId): String? = redis.opsForValue().get("bank:{${uid.value}}:$d").awaitSingleOrNull()

        private suspend fun pexpiretime(uid: UserId): Long =
            redis.execute(PEXPIRETIME, listOf("bank:{${uid.value}}:$d")).awaitSingle()

        private companion object {
            val seq = AtomicInteger()
            val PEXPIRETIME: RedisScript<Long> = RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long::class.java)
        }
    }
