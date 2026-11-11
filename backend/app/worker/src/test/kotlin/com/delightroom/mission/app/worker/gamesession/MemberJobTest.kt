package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.service.RosterService
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `worker: member`(ORACLE game-session O27 · O28 · O30 · I4 — LLD §3.5). 잡 본문 [MemberJob.run] 을 직접 부른다.
 *
 * `D` 는 KST 오늘 + 7일이다(ORACLE v4.1) — O30 은 Redis 실제 시계로 만료를 보므로 앱 시계를 실제 시각에 두고, 그러면 `D+1 08:00` 이
 * 지금보다 뒤여야 한다. 그날 · 다음 날 파티션은 다른 테스트가 쓰지 않아 지우고 잡이 만드는지 본다.
 * `api` 경로(O28 · I4)는 [RosterService] 를 시계 `D 07:00` 에서 부른다. 잡은 DB 의 모든 그룹을 돌므로 판정은 이 테스트가 만든 그룹으로 좁힌다.
 */
// 페이지 2개 — 여러 페이지(Q7 의 id > after)를 넘어가는 길을 탄다
@SpringBootTest(
    properties = [
        "worker.persister.enabled=false",
        "worker.prepare-questions.enabled=false",
        "worker.member.enabled=false",
        "worker.member.page-size=2",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
@Import(MutableClockConfiguration::class, MemberJobTest.Counting::class)
class MemberJobTest
    @Autowired
    constructor(
        private val job: MemberJob,
        private val rosterService: RosterService,
        private val clock: MutableClock,
        private val keys: RedisKeys,
        private val redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
        private val connectionFactory: ConnectionFactory,
    ) {
        /** `createIfAbsent` 가 `true` 를 낸 횟수를 (그룹, 날짜)마다 센다 — I4 의 「`created = 1` 정확히 1번」. */
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
                delegate.createIfAbsent(roster, restore).also { if (it) created.computeIfAbsent(roster.gid to roster.date) { AtomicInteger() }.incrementAndGet() }
        }

        private class Fixture(val a: GroupId, val s: GroupId, val z: GroupId, val aMembers: Map<String, UserId>)

        @BeforeEach
        fun setUp(): Unit = runBlocking {
            // worker 는 스키마를 적용하지 않는다 — api 와 같은 schema.sql 을 테스트가 적용한다
            ResourceDatabasePopulator(ClassPathResource("schema.sql")).populate(connectionFactory).awaitSingleOrNull()
            clock.set(Instant.now())
        }

        // dropPartitions 는 D 파티션이 이미 있어도(다른 테스트가 먼저 잡을 돌림) 지운다 — 테스트 순서에 기대지 않는다
        @Test
        fun `O27 잡 한 번이면 명단 A=3 S=1 Z=0 이고 그룹 Hash 와 -1 행은 A 만 D+1 파티션 4개`(): Unit = runBlocking {
            job.run(D) // 파티션이 이미 있는 상태에서 지우는 길을 늘 탄다(테스트 순서와 상관없이)
            dropPartitions(D)
            dropPartitions(D.plusDays(1))
            assertThat(partitionCount(D) + partitionCount(D.plusDays(1))).isZero()
            val f = fixture()

            val summary = job.run(D)

            assertThat(summary.failedPages).isZero()
            assertThat(summary.groups).isGreaterThanOrEqualTo(3)
            assertThat(hash(keys.members(f.a, D))["#size"]).isEqualTo("3")
            assertThat(hash(keys.members(f.s, D))["#size"]).isEqualTo("1")
            assertThat(hash(keys.members(f.z, D))["#size"]).isEqualTo("0")
            assertThat(hash(keys.members(f.a, D)).keys - setOf("#size", "#name"))
                .containsExactlyInAnyOrderElementsOf(f.aMembers.filterKeys { it != "late_d" }.values.map { it.value.toString() })

            val group = hash(keys.group(f.a, D))
            assertThat(group["version"]).isEqualTo("0")
            assertThat(group["size"]).isEqualTo("3")
            for (prefix in listOf("alice_a", "bob_b", "carol_c")) {
                val uid = f.aMembers.getValue(prefix).value
                assertThat(group["$uid:p"]).describedAs("$prefix p").isEqualTo("-1")
                assertThat(group["$uid:st"]).describedAs("$prefix st").isEqualTo("NOT_STARTED")
            }
            assertThat(group).doesNotContainKey("${f.aMembers.getValue("late_d").value}:p")
            assertThat(exists(keys.group(f.s, D))).isFalse()
            assertThat(exists(keys.group(f.z, D))).isFalse()
            assertThat(isMember(keys.dayGroups(keys.bucket(f.a), D), f.a)).isTrue()
            assertThat(isMember(keys.dayGroups(keys.bucket(f.s), D), f.s)).isFalse()
            assertThat(isMember(keys.dayGroups(keys.bucket(f.z), D), f.z)).isFalse()

            assertThat(progressRows(f.a)).containsOnly(-1).hasSize(3)
            assertThat(progressRows(f.s)).isEmpty()
            assertThat(progressRows(f.z)).isEmpty()
            assertThat(partitionCount(D.plusDays(1))).isEqualTo(4)
            assertThat(partitionCount(D)).isEqualTo(4)
        }

        @Test
        fun `O28 다시 돌려도 api 가 먼저 만든 명단과 시작한 그룹원을 덮지 않고 두 번째 실행은 created 0`(): Unit = runBlocking {
            val f = fixture()
            job.run(D) // 파티션 · 앞서 있던 그룹을 먼저 확정해 두 번째 실행의 created 판정을 이 테스트 그룹으로 좁힌다
            val f2 = fixture()
            clock.set(OffsetDateTime.parse("${D}T07:00:05+09:00").toInstant())
            rosterService.findOrCreate(f2.a, D) // api 경로(§3.4) — 명단 · 그룹 Hash · -1 행
            val alice = f2.aMembers.getValue("alice_a").value
            // alice_a 시작 — start.lua(T-GS-3)가 그룹 Hash 에 남기는 것을 직접 쓴다
            redis.opsForHash<String, String>().putAll(keys.group(f2.a, D), mapOf("$alice:p" to "0", "$alice:st" to "IN_PROGRESS")).awaitSingle()
            redis.opsForHash<String, String>().increment(keys.group(f2.a, D), "version", 1).awaitSingle()

            val first = job.run(D)
            val second = job.run(D)

            assertThat(first.created).describedAs("api 가 만든 A 는 빼고 S · Z").isEqualTo(2)
            assertThat(second.created).isZero()
            assertThat(second.groups).isGreaterThanOrEqualTo(6)
            val group = hash(keys.group(f2.a, D))
            assertThat(group["$alice:p"]).isEqualTo("0")
            assertThat(group["$alice:st"]).isEqualTo("IN_PROGRESS")
            assertThat(group["version"]).isEqualTo("1")
            assertThat(progressRows(f2.a)).hasSize(3)
            assertThat(progressRows(f.a)).hasSize(3)
        }

        @Test
        fun `O28 api 가 명단만 만들고 -1 행을 못 넣었으면 잡이 채운다`(): Unit = runBlocking {
            job.run(D)
            val f = fixture()
            clock.set(OffsetDateTime.parse("${D}T07:00:05+09:00").toInstant())
            rosterService.findOrCreate(f.a, D)
            db.sql("DELETE FROM daily_progress WHERE game_date = :d AND group_id = :g")
                .bind("d", D).bind("g", f.a.value).then().awaitSingleOrNull()

            val summary = job.run(D)

            assertThat(summary.created).describedAs("S · Z").isEqualTo(2)
            assertThat(progressRows(f.a)).containsOnly(-1).hasSize(3)
        }

        @Test
        fun `O30 명단 · 그룹 Hash · 그날 그룹 목록은 D+1 08시에 만료된다`(): Unit = runBlocking {
            val f = fixture()
            // 남은 ms 는 앱 시계로 계산하고 Redis 는 제 시계로 PEXPIRE 한다 — 앱 시계를 실행 직전 실제 시각에 두고 이 그룹들만 돈다
            clock.set(Instant.now())
            job.run(D, startAfter = GroupId(f.a.value - 1))

            val expected = OffsetDateTime.parse("${D.plusDays(1)}T08:00:00+09:00").toEpochSecond()
            for (key in listOf(keys.members(f.a, D), keys.members(f.s, D), keys.members(f.z, D), keys.group(f.a, D), keys.dayGroups(keys.bucket(f.a), D))) {
                assertThat(expireTime(key)).describedAs("EXPIRETIME $key").isBetween(expected - 1, expected + 1)
            }
        }

        @Test
        fun `I4 07시에 잡과 같은 그룹 요청 50건이 겹쳐도 명단은 한 번 만든다`(): Unit = runBlocking {
            job.run(D) // 앞서 있던 그룹을 먼저 확정 — 회마다 잡이 새 그룹에 빨리 닿게
            clock.set(OffsetDateTime.parse("${D}T07:00:00+09:00").toInstant())
            var workerWins = 0
            repeat(20) { round ->
                val f = fixture()
                val ids = f.aMembers.filterKeys { it != "late_d" }.values.map { it.value.toString() }
                val results = coroutineScope {
                    val requests = (0 until 50).map { async(Dispatchers.Default) { runCatching { rosterService.findOrCreate(f.a, D) } } }
                    // 잡이 이 그룹에 바로 닿게 그 앞에서 시작한다 — 요청 50건과 실제로 겹치게
                    val worker = async(Dispatchers.Default) { job.run(D, startAfter = GroupId(f.a.value - 1)) }
                    val summary = worker.await()
                    if (summary.created > 0) workerWins++
                    requests.awaitAll()
                }
                assertThat(results.filter { it.isFailure }).describedAs("회 $round 실패 ${results.mapNotNull { it.exceptionOrNull() }}").isEmpty()
                assertThat(results.map { r -> r.getOrThrow().members.keys.map { it.value.toString() }.toSet() }).allMatch { it == ids.toSet() }
                assertThat(created[f.a to D]?.get()).describedAs("회 $round created = 1 횟수").isEqualTo(1)
                val group = hash(keys.group(f.a, D))
                assertThat(group.keys.filter { it.endsWith(":p") }.map { it.substringBefore(':') }).containsExactlyInAnyOrderElementsOf(ids)
                assertThat(progressRows(f.a)).hasSize(3)
            }
            // 누가 이겼는지는 판정하지 않는다(경합) — 잡이 이 그룹에 닿은 횟수를 남겨 겹침이 실제로 일어났는지 본다
            println("I4 worker-created-rounds=$workerWins/20")
        }

        @Test
        fun `O27 (바탕) 다음 실행은 지금보다 뒤인 가장 이른 07시이고 기동 따라잡기는 07시 ~ 08시만`() {
            fun at(s: String) = OffsetDateTime.parse(s).toInstant()
            val day = LocalDate.parse("2026-11-12")
            assertThat(job.nextRunDate(at("2026-11-12T06:59:59.999+09:00"))).isEqualTo(day)
            assertThat(job.nextRunDate(at("2026-11-12T07:00:00+09:00"))).isEqualTo(day.plusDays(1))
            assertThat(job.nextRunDate(at("2026-11-12T23:59:59+09:00"))).isEqualTo(day.plusDays(1))
            assertThat(job.nextRunDate(at("2026-11-11T15:00:00Z"))).describedAs("UTC 자정 넘김 = KST 00:00").isEqualTo(day)
            assertThat(job.catchUpDate(at("2026-11-12T06:59:59+09:00"))).isNull()
            assertThat(job.catchUpDate(at("2026-11-12T07:00:00+09:00"))).isEqualTo(day)
            assertThat(job.catchUpDate(at("2026-11-12T07:59:59.999+09:00"))).isEqualTo(day)
            assertThat(job.catchUpDate(at("2026-11-12T08:00:00+09:00"))).isNull()
        }

        // --- 고정 데이터(ORACLE §0): A(06:00 × 3 + 07:30 × 1) · S(06:00 × 1) · Z(07:10 × 1) ---

        private suspend fun fixture(): Fixture {
            val tag = "${RUN}${SEQ.incrementAndGet()}"
            val a = group("A-$tag")
            val aMembers = listOf("alice_a" to "06:00", "bob_b" to "06:00", "carol_c" to "06:00", "late_d" to "07:30")
                .associate { (prefix, at) -> prefix to user(a, "${prefix}_$tag", at) }
            val s = group("S-$tag")
            user(s, "solo_s_$tag", "06:00")
            val z = group("Z-$tag")
            user(z, "zero_z_$tag", "07:10")
            return Fixture(a, s, z, aMembers)
        }

        private suspend fun group(name: String): GroupId =
            GroupId(
                db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, lower(:n), 0) RETURNING id")
                    .bind("n", name).map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle(),
            )

        private suspend fun user(gid: GroupId, loginId: String, kst: String): UserId =
            UserId(
                db.sql("INSERT INTO users (login_id, password_hash, group_id, joined_at) VALUES (:l, 'x', :g, :j) RETURNING id")
                    .bind("l", loginId).bind("g", gid.value).bind("j", OffsetDateTime.parse("${D}T$kst:00+09:00"))
                    .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle(),
            )

        /**
         * [date] 의 파티션 4개를 지운다. `solve_signals` → `daily_progress` · `group_result_members` → `group_results` FK 가 부모 테이블에 걸려 있어
         * 참조하는 쪽을 먼저 지우고, 참조되는 쪽은 떼어 낸(DETACH) 뒤 지운다(CASCADE 는 부모의 FK 를 지운다).
         */
        private suspend fun dropPartitions(date: LocalDate) {
            val suffix = date.toString().replace("-", "")
            for (t in listOf("solve_signals", "group_result_members")) db.sql("DROP TABLE IF EXISTS ${t}_$suffix").then().awaitSingleOrNull()
            for (t in listOf("daily_progress", "group_results")) {
                val exists = partitionCount(date, listOf(t)) > 0
                if (exists) {
                    db.sql("ALTER TABLE $t DETACH PARTITION ${t}_$suffix").then().awaitSingleOrNull()
                    db.sql("DROP TABLE ${t}_$suffix").then().awaitSingleOrNull()
                }
            }
        }

        private suspend fun partitionCount(date: LocalDate, tables: List<String> = TABLES): Long {
            val suffix = date.toString().replace("-", "")
            return db.sql("SELECT count(*) FROM pg_class WHERE relname = ANY(:names)")
                .bind("names", tables.map { "${it}_$suffix" }.toTypedArray())
                .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle()
        }

        private suspend fun progressRows(gid: GroupId): List<Int> =
            db.sql("SELECT progress FROM daily_progress WHERE game_date = :d AND group_id = :g")
                .bind("d", D).bind("g", gid.value)
                .map { row -> (row.get(0) as Number).toInt() }.all().collectList().awaitSingle()

        private suspend fun hash(key: String): Map<String, String> =
            redis.opsForHash<String, String>().entries(key).collectMap({ it.key }, { it.value }).awaitSingle()

        private suspend fun exists(key: String): Boolean = redis.hasKey(key).awaitSingle()

        private suspend fun isMember(key: String, gid: GroupId): Boolean =
            redis.opsForSet().isMember(key, gid.value.toString()).awaitSingle()

        private suspend fun expireTime(key: String): Long = redis.execute(EXPIRETIME, listOf(key)).awaitSingle()

        private companion object {
            val D: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(7)
            val TABLES = listOf("daily_progress", "solve_signals", "group_results", "group_result_members")
            val RUN = System.currentTimeMillis().toString(36)
            val SEQ = AtomicInteger()
            val EXPIRETIME: RedisScript<Long> = RedisScript.of("return redis.call('EXPIRETIME', KEYS[1])", Long::class.java)
            val created = ConcurrentHashMap<Pair<GroupId, LocalDate>, AtomicInteger>()
        }
    }
