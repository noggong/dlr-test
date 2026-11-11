package com.delightroom.mission.app.api.deadlineresult

import com.delightroom.mission.app.api.auth.AuthApiTestSupport
import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.TokenIssuer
import com.delightroom.mission.core.testfixture.RankingCases
import com.delightroom.mission.infra.redis.RedisScripts
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveRedisCallback
import org.springframework.test.web.reactive.server.EntityExchangeResult
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * ORACLE deadline-result O18 ~ O26 · O30 · O31 — `GET /api/v1/games/results/latest`(LLD §3.3 · SPEC DR1 · DR2 · DR8 ~ DR12).
 *
 * - 오늘 Hash 경로(O18 ~ O20)는 그룹 Hash 를 직접 쓰고 Lua `deadline` 을 경계 `D = TIME − 0.5초`(1차) · `TIME − 3.5초`(2차)로
 *   부른다(DeadlineLuaTest 와 같은 방식). 게임 날짜는 `RankingCases` 의 날짜(2026-11-11)이고, API 시계는 [MutableClock] 으로 옮긴다.
 * - 인증은 토큰을 직접 발급한다(필터는 저장소를 보지 않는다). PostgreSQL 경로(O22 · O25)만 실제 가입 유저 · 그룹을 쓴다(FK).
 * - 「PostgreSQL 쿼리 수」는 [CountingResults] 가 `findLatest` 호출을 센다 — 결과 조회에서 PostgreSQL 을 읽는 길은 그것 하나다.
 */
@Import(LatestResultApiTest.CountingConfig::class)
class LatestResultApiTest : AuthApiTestSupport() {
    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var gameProperties: GameProperties

    @Autowired
    private lateinit var scripts: RedisScripts

    @Autowired
    private lateinit var counting: CountingResults

    private val json = jacksonObjectMapper()
    private val date: LocalDate = RankingCases.file.gameDate
    private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    /** 이 테스트가 만든 하루 파티션(부모, 이름) — 만든 순서의 반대로 떼어 지운다. */
    private val createdPartitions = mutableListOf<Pair<String, String>>()

    /** 이 테스트가 결과 행을 넣은 (그룹, 날짜) — 행과 그 그룹의 결과 캐시를 치운다. */
    private val storedResults = mutableListOf<Pair<GroupId, LocalDate>>()

    @AfterEach
    fun cleanUp() = runBlocking {
        redis.delete(redisKeys.group(gid, date), redisKeys.members(gid, date), redisKeys.result(gid)).awaitSingle()
        storedResults.forEach { (groupId, day) ->
            redis.delete(redisKeys.result(groupId)).awaitSingle()
            for (t in listOf("group_result_members", "group_results")) {
                db.sql("DELETE FROM $t WHERE game_date = '$day' AND group_id = ${groupId.value}").then().awaitSingleOrNull()
            }
        }
        storedResults.clear()
        createdPartitions.reversed().forEach { (parent, name) ->
            db.sql("ALTER TABLE $parent DETACH PARTITION $name").then().awaitSingleOrNull()
            db.sql("DROP TABLE $name").then().awaitSingleOrNull()
        }
        createdPartitions.clear()
    }

    // ---- 요청 ----

    private fun access(loginId: String, groupId: GroupId = gid, uid: Long = 1): String =
        tokenIssuer.issue(
            Account(UserId(uid), LoginId(loginId), "", groupId, GroupName("결과반")),
            UUID.randomUUID().toString(),
            gameProperties.now(clock),
        ).access

    private fun latest(access: String?): EntityExchangeResult<Map<String, Any>> =
        client.get().uri(LATEST)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .exchange().expectBody(MAP_TYPE).returnResult()

    /** [loginId] 로 조회 — 200 이어야 한다. */
    private fun ok(loginId: String, groupId: GroupId = gid): Map<String, Any?> {
        val result = latest(access(loginId, groupId))
        assertThat(result.status.value()).describedAs("본문 %s", result.responseBody).isEqualTo(200)
        return result.responseBody!!
    }

    // ---- 그룹 Hash · Lua ----

    /** 그룹원 한 줄(LLD game-session §2.2 필드). */
    private data class M(
        val uid: String,
        val lid: String,
        val st: String,
        val p: Int,
        val cur: String? = null,
        val ls: Long? = null,
        val pn: Int? = null,
        val late: Boolean? = if (p >= 0) false else null,
    )

    private fun ls(time: String): Long = RankingCases.epochMicros(LocalTime.parse(time))

    /** 07:00 `roster` 가 만든 모양 + 그룹원 상태 · `version` 0. */
    private fun setUpHash(members: List<M>, extra: Map<String, String> = emptyMap()) = runBlocking {
        val fields = linkedMapOf("date" to "$date", "gid" to "${gid.value}", "size" to "${members.size}", "version" to "0")
        members.forEach { m ->
            fields["${m.uid}:lid"] = m.lid
            fields["${m.uid}:st"] = m.st
            fields["${m.uid}:p"] = "${m.p}"
            m.cur?.let { fields["${m.uid}:cur"] = it }
            m.ls?.let { fields["${m.uid}:ls"] = "$it" }
            m.pn?.let { fields["${m.uid}:pn"] = "$it" }
            m.late?.let { fields["${m.uid}:late"] = if (it) "1" else "0" }
        }
        fields += extra
        redis.opsForHash<String, String>().putAll(redisKeys.group(gid, date), fields).awaitSingle()
        val roster = linkedMapOf("#size" to "${members.size}", "#name" to "g$gid")
        members.forEach { roster[it.uid] = it.lid }
        redis.opsForHash<String, String>().putAll(redisKeys.members(gid, date), roster).awaitSingle()
    }

    private fun hash(): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(redisKeys.group(gid, date)).collectList().awaitSingle()
            .associate { it.key to it.value }
    }

    private fun redisTimeUs(): Long = runBlocking {
        redis.execute(ReactiveRedisCallback { it.serverCommands().time(TimeUnit.MICROSECONDS) }).awaitSingle()
    }

    private fun deadline(phase: Int, deadlineUs: Long): DeadlineCode = runBlocking {
        val reply = scripts.eval(
            "deadline",
            listOf(redisKeys.group(gid, date), redisKeys.members(gid, date), redisKeys.events(gid)),
            listOf("$phase", "$date", "${gid.value}", "$deadlineUs", "${deadlineUs + 3_000_000L}", redisKeys.channel(gid), "0"),
        )
        DeadlineCode.valueOf(reply[0] as String)
    }

    /** 08:00:00 ≤ t < 08:00:03. */
    private fun phase1() = deadline(1, redisTimeUs() - 500_000L)

    /** t ≥ 08:00:03. */
    private fun phase2() = deadline(2, redisTimeUs() - 3_500_000L)

    /** §1.2 S3 — `amy0` 이 3번을 풀고 있다. */
    private val s3 = listOf(
        M("1", "amy0", "IN_PROGRESS", 2, cur = "q3", ls = ls("07:30:00")),
        M("2", "ben0", "SUCCEEDED", 3, ls = ls("07:40:00")),
    )

    /** §1.1 G1 의 1차 전 상태 — `gus0` · `ivy0` IN_PROGRESS(cur 없음) · `hal0` 진행도 1 · `cur` 2번(DeadlineLuaTest 와 같다). */
    private fun g1BeforeDeadline(): List<M> =
        RankingCases.groups.single { it.id == "G1" }.members.mapIndexed { i, m ->
            val k = RankingCases.key(m)
            val base = M("${i + 1}", m.loginId, m.status, m.progress, ls = k.lastSolvedAtUs, pn = k.passNo, late = k.late)
            when (m.loginId) {
                "gus0", "ivy0" -> base.copy(st = "IN_PROGRESS")
                "hal0" -> base.copy(st = "IN_PROGRESS", p = 1, cur = "q2", ls = ls("07:10:00"))
                else -> base
            }
        }

    // ---- 결과 캐시 · PostgreSQL ----

    /** §2.4 모양의 캐시 값 — [ranking] 은 (userId, rank, progress, status, started, lastSolvedAt, passedAtQuestionNo). */
    private fun cacheJson(
        day: LocalDate,
        ranking: List<Map<String, Any?>> = listOf(
            entry("amy0", 1, 3, "SUCCEEDED", true, "${day}T07:20:00.000000+09:00", null),
            entry("ivy0", 2, 0, "FAILED", true, null, 1),
            entry("hu_1", 3, 0, "FAILED", false, null, null),
        ),
    ): String =
        json.writeValueAsString(
            linkedMapOf(
                "date" to "$day",
                "groupStatus" to "FAILED",
                "decidedAt" to "${day}T08:00:00.000412+09:00",
                "rankingFinal" to true,
                "ranking" to ranking,
            ),
        )

    private fun entry(id: String, rank: Int, p: Int, st: String, started: Boolean, at: String?, pn: Int?) =
        linkedMapOf<String, Any?>(
            "userId" to id, "rank" to rank, "progress" to p, "status" to st, "started" to started,
            "lastSolvedAt" to at, "passedAtQuestionNo" to pn,
        )

    private fun putCache(raw: String, groupId: GroupId = gid) = runBlocking {
        redis.opsForValue().set(redisKeys.result(groupId), raw, Duration.ofSeconds(90_000)).awaitSingle()
    }

    private fun cached(groupId: GroupId = gid): String? = runBlocking {
        redis.opsForValue().get(redisKeys.result(groupId)).awaitSingleOrNull()
    }

    private fun ttl(groupId: GroupId): Long = runBlocking { redis.getExpire(redisKeys.result(groupId)).awaitSingle().seconds }

    private fun exec(sql: String) = runBlocking { db.sql(sql).then().awaitSingleOrNull() }

    /** 지난 날짜의 하루 파티션 — 기동은 KST 오늘 · 내일만 만든다. 이 테스트가 새로 만든 것만 치운다. */
    private fun ensurePartitions(day: LocalDate) {
        for (t in listOf("group_results", "group_result_members")) {
            val name = "${t}_${day.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            val exists = queryLong("SELECT count(*) FROM pg_class WHERE relname = :n", "n" to name)!! > 0
            if (exists) continue
            exec("CREATE TABLE $name PARTITION OF $t FOR VALUES FROM ('$day') TO ('${day.plusDays(1)}')")
            createdPartitions += t to name
        }
    }

    /** 가입한 3명 그룹과 그 그룹의 [day] 결과 행(`FAILED_DEADLINE`) — (그룹 id, 로그인 아이디 3개). */
    private fun storedResult(day: LocalDate): Pair<GroupId, List<String>> {
        val name = uniqueGroupName("Res")
        val ids = groupOf(name, 3)
        val groupId = GroupId(queryLong("SELECT id FROM groups WHERE name_key = lower(:n)", "n" to name)!!)
        ensurePartitions(day)
        storedResults += groupId to day
        exec(
            "INSERT INTO group_results (game_date, group_id, result, completed_at, member_count) " +
                "VALUES ('$day', ${groupId.value}, 'FAILED_DEADLINE', '${day}T08:00:00.000412+09:00', 3)",
        )
        // 세 번째는 미접속(-1) · 순위는 ORDER BY 를 보려고 거꾸로 넣는다
        val rows = listOf(
            "${userIdOf(ids[2]).value}, 3, -1, 'FAILED', false, NULL, NULL, false",
            "${userIdOf(ids[1]).value}, 2, 0, 'FAILED', true, NULL, 1, false",
            "${userIdOf(ids[0]).value}, 1, 3, 'SUCCEEDED', true, '${day}T07:20:00.000001+09:00', NULL, false",
        )
        rows.forEach { row ->
            exec(
                "INSERT INTO group_result_members (game_date, group_id, user_id, rank, progress, status, started, " +
                    "last_solved_at, pass_question_no, cheat_failed) " +
                    "VALUES ('$day', ${groupId.value}, $row)",
            )
        }
        return groupId to ids
    }

    // ---- 판정 도우미 ----

    @Suppress("UNCHECKED_CAST")
    private fun ranking(body: Map<String, Any?>): List<Map<String, Any?>> = body["ranking"] as List<Map<String, Any?>>

    /** O31 — 결과 상태 값 · 진행도 범위 · 미시작 줄의 진행도. */
    private fun assertResultEntries(body: Map<String, Any?>) {
        val rows = ranking(body)
        assertThat(rows).isNotEmpty
        rows.forEach { row ->
            assertThat(row.keys).containsExactlyInAnyOrder(
                "userId", "rank", "progress", "status", "started", "lastSolvedAt", "passedAtQuestionNo",
            )
            assertThat(row["status"]).isIn("SUCCEEDED", "CHEAT_FAILED", "FAILED")
            assertThat(row["progress"] as Int).isBetween(0, 3)
            if (row["started"] == false) assertThat(row["progress"]).isEqualTo(0)
        }
    }

    // ---- O18 · O19 · O20 — 오늘 Hash ----

    @Test
    fun `O18 PENDING 모양 — S3 1차 뒤 08시 00분 01초`() {
        setUpHash(s3)
        assertThat(phase1()).isEqualTo(DeadlineCode.PENDING)
        setClock("${date}T08:00:01+09:00")

        val body = ok("amy0")

        assertThat(body.keys).containsExactly(
            "date", "groupStatus", "decidedAt", "rankingFinal", "myRank", "ranking", "nextGameStartAt",
        )
        assertThat(body).containsEntry("date", "$date").containsEntry("groupStatus", "PENDING")
            .containsEntry("decidedAt", null).containsEntry("rankingFinal", false).containsEntry("myRank", null)
            .containsEntry("ranking", emptyList<Any>())
            .containsEntry("nextGameStartAt", "${date.plusDays(1)}T07:00:00+09:00")
    }

    @Test
    fun `O19 워커 처리 전도 PENDING 이고 요청이 확정하지 않는다`() {
        setUpHash(s3)
        setClock("${date}T08:00:01+09:00")
        val before = hash()

        val body = ok("ben0")

        assertThat(body["groupStatus"]).isEqualTo("PENDING")
        assertThat(body["ranking"]).isEqualTo(emptyList<Any>())
        assertThat(hash()).doesNotContainKey("result").isEqualTo(before)
        assertThat(cached()).isNull()
    }

    @Test
    fun `O20 오늘 결과 잠정에서 확정 — date · decidedAt 같고 myRank 는 내 줄 rank (S2)`() {
        val members = g1BeforeDeadline()
        val hal = members.single { it.lid == "hal0" }.uid
        setUpHash(members)
        assertThat(phase1()).isEqualTo(DeadlineCode.DECIDED)
        setClock("${date}T08:00:01+09:00")
        val first = ok("hal0")

        // hal0 의 2번 유예 풀이(도착 08:00:01.5) — solve-signal Lua `solve` 가 쓰는 필드
        runBlocking {
            val ops = redis.opsForHash<String, String>()
            ops.putAll(redisKeys.group(gid, date), mapOf("$hal:p" to "2", "$hal:ls" to "${ls("08:00:01.5")}")).awaitSingle()
            ops.remove(redisKeys.group(gid, date), "$hal:cur").awaitSingle()
            ops.increment(redisKeys.group(gid, date), "version", 1).awaitSingle()
        }
        assertThat(phase2()).isEqualTo(DeadlineCode.FINALIZED)
        setClock("${date}T08:00:04+09:00")
        val second = ok("hal0")

        assertThat(first).containsEntry("groupStatus", "FAILED").containsEntry("rankingFinal", false)
        assertThat(second).containsEntry("groupStatus", "FAILED").containsEntry("rankingFinal", true)
        assertThat(second["date"]).isEqualTo(first["date"]).isEqualTo("$date")
        assertThat(second["decidedAt"]).isEqualTo(first["decidedAt"])
        // decidedAt 은 Lua 가 쓴 Redis TIME(이 테스트는 경계를 지금 기준으로 잡는다) — µs 6자리 · +09:00 형식만 본다
        assertThat(first["decidedAt"] as String).matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+09:00""")
        for (body in listOf(first, second)) {
            assertThat(body["myRank"]).isEqualTo(ranking(body).single { it["userId"] == "hal0" }["rank"])
            assertResultEntries(body) // O31
        }
        // 2차 뒤 랭킹 = §1.1 G1(표시 순서 · 순위) · hal0 의 유예 풀이 반영
        val expected = RankingCases.groups.single { it.id == "G1" }.members.sortedBy { it.expectedOrder }
        assertThat(ranking(second).map { it["userId"] to it["rank"] })
            .containsExactlyElementsOf(expected.map { it.loginId to it.expectedRank })
        assertThat(ranking(second).single { it["userId"] == "hal0" })
            .containsEntry("progress", 2).containsEntry("lastSolvedAt", "${date}T08:00:01.500000+09:00")
        assertThat(ranking(second).single { it["userId"] == "amy0" })
            .containsEntry("lastSolvedAt", "${date}T07:20:00.000000+09:00").containsEntry("status", "SUCCEEDED")
        assertThat(second["myRank"]).isEqualTo(8)
        // Hash 경로는 캐시를 쓰지 않는다(캐시 쓰기는 persister — T-DR-6)
        assertThat(cached()).isNull()
    }

    @Test
    fun `O20 08시 전 조기 확정은 그 자리에서 오늘 결과 (DR1 2)`() {
        val members = listOf(
            M("1", "amy0", "SUCCEEDED", 3, ls = ls("07:31:00")),
            M("2", "ben0", "SUCCEEDED", 3, ls = ls("07:40:12.345678"), pn = 2),
        )
        val ranking = """[{"u":"ben0","r":2,"id":"2","p":3,"s":"SUCCEEDED","l":"${ls("07:40:12.345678")}","n":2},""" +
            """{"r":1,"id":"1","u":"amy0","s":"SUCCEEDED","p":3,"l":"${ls("07:31:00")}","n":null}]"""
        setUpHash(
            members,
            mapOf("result" to "SUCCEEDED", "decidedAt" to "${ls("07:40:12.345678")}", "rankingFinal" to "1", "ranking" to ranking),
        )
        putCache(cacheJson(date.minusDays(1)))
        setClock("${date}T07:41:00+09:00")

        val body = ok("ben0")

        assertThat(body).containsEntry("date", "$date").containsEntry("groupStatus", "SUCCEEDED")
            .containsEntry("decidedAt", "${date}T07:40:12.345678+09:00").containsEntry("rankingFinal", true)
            .containsEntry("myRank", 2).containsEntry("nextGameStartAt", "${date.plusDays(1)}T07:00:00+09:00")
        assertThat(ranking(body)).containsExactly(
            entry("amy0", 1, 3, "SUCCEEDED", true, "${date}T07:31:00.000000+09:00", null),
            entry("ben0", 2, 3, "SUCCEEDED", true, "${date}T07:40:12.345678+09:00", 2),
        )
    }

    @Test
    fun `O25 07시 ~ 08시 결과 전에는 직전 게임 결과 (DR1 3)`() {
        setUpHash(s3)
        putCache(cacheJson(date.minusDays(1)))
        setClock("${date}T07:59:59+09:00")

        val body = ok("amy0")

        assertThat(body).containsEntry("date", "${date.minusDays(1)}").containsEntry("groupStatus", "FAILED")
            .containsEntry("myRank", 1).containsEntry("nextGameStartAt", "${date.plusDays(1)}T07:00:00+09:00")
    }

    // ---- O21 · O22 · O23 — 캐시 · PostgreSQL ----

    @Test
    fun `O21 캐시 적중이면 PostgreSQL 쿼리 0`() {
        val raw = cacheJson(date.minusDays(1))
        putCache(raw)
        setClock("${date}T07:30:00+09:00")
        val before = counting.calls.get()

        val body = ok("ivy0")

        val expected = json.readValue<Map<String, Any?>>(raw) +
            mapOf("myRank" to 2, "nextGameStartAt" to "${date.plusDays(1)}T07:00:00+09:00")
        assertThat(body).isEqualTo(expected)
        assertThat(counting.calls.get() - before).isEqualTo(0)
        assertThat(cached()).isEqualTo(raw)
    }

    @Test
    fun `O22 캐시 없음이면 PostgreSQL 에서 읽어 캐시를 채운다`() {
        val day = LocalDate.of(2026, 10, 20)
        val (groupId, ids) = storedResult(day)
        setClock("2026-10-21T07:30:00+09:00")
        val before = counting.calls.get()

        val body = ok(ids[1], groupId)

        assertThat(counting.calls.get() - before).isEqualTo(1)
        assertThat(body).containsEntry("date", "$day").containsEntry("groupStatus", "FAILED")
            .containsEntry("decidedAt", "${day}T08:00:00.000412+09:00").containsEntry("rankingFinal", true)
            .containsEntry("myRank", 2).containsEntry("nextGameStartAt", "2026-10-22T07:00:00+09:00")
        assertThat(ranking(body)).containsExactly(
            entry(ids[0], 1, 3, "SUCCEEDED", true, "${day}T07:20:00.000001+09:00", null),
            entry(ids[1], 2, 0, "FAILED", true, null, 1),
            entry(ids[2], 3, 0, "FAILED", false, null, null), // 진행도 -1 → 0 · started false
        )
        assertResultEntries(body) // O31
        assertThat(cached(groupId)).isNotNull
        assertThat(ttl(groupId)).isBetween(89_990L, 90_000L)
        val cache = json.readValue<Map<String, Any?>>(cached(groupId)!!)
        assertThat(cache.keys).containsExactly("date", "groupStatus", "decidedAt", "rankingFinal", "ranking")
        assertThat(cache["ranking"]).isEqualTo(body["ranking"])

        // 두 번째 조회는 캐시에서 — 같은 본문 · PostgreSQL 쿼리 늘지 않음(DR11)
        assertThat(ok(ids[1], groupId)).isEqualTo(body)
        assertThat(counting.calls.get() - before).isEqualTo(1)
    }

    @Test
    fun `O22 PostgreSQL 의 오늘 결과는 08시 00분 03초 전이면 rankingFinal false 이고 캐시하지 않는다`() {
        val day = LocalDate.of(2026, 10, 25)
        val (groupId, ids) = storedResult(day)
        setClock("${day}T08:00:02+09:00")

        val body = ok(ids[0], groupId)

        assertThat(body).containsEntry("date", "$day").containsEntry("rankingFinal", false).containsEntry("myRank", 1)
        // 잠정 랭킹은 캐시하지 않는다 — 08:00:03 뒤 조회가 확정으로 보이게(LLD §3.3 4 · DR5)
        assertThat(cached(groupId)).isNull()
        setClock("${day}T08:00:04+09:00")
        assertThat(ok(ids[0], groupId)).containsEntry("rankingFinal", true)
        assertThat(cached(groupId)).isNotNull
    }

    @Test
    fun `O22 읽지 못하는 캐시 값은 버리고 PostgreSQL 로 다시 채운다`() {
        val day = LocalDate.of(2026, 10, 26)
        val (groupId, ids) = storedResult(day)
        putCache("""{"date":"2026-10-26","groupStatus":"NO_RESULT"}""", groupId)
        setClock("2026-10-27T07:30:00+09:00")

        val body = ok(ids[0], groupId)

        assertThat(body).containsEntry("date", "$day").containsEntry("myRank", 1)
        assertThat(json.readValue<Map<String, Any?>>(cached(groupId)!!)["groupStatus"]).isEqualTo("FAILED")
    }

    @Test
    fun `O23 결과가 한 번도 없으면 404 NO_RESULT_YET 이고 캐시하지 않는다 — 1명 그룹`() {
        val name = uniqueGroupName("Solo")
        val solo = groupOf(name, 1).single()
        val soloGroup = GroupId(queryLong("SELECT id FROM groups WHERE name_key = lower(:n)", "n" to name)!!)
        setClock("${date}T09:00:00+09:00")
        val before = counting.calls.get()

        val result = latest(access(solo, soloGroup, userIdOf(solo).value))

        assertThat(result.status.value()).isEqualTo(404)
        assertThat(result.responseBody).containsEntry("code", "NO_RESULT_YET")
        assertThat(counting.calls.get() - before).isEqualTo(1)
        assertThat(cached(soloGroup)).isNull()
    }

    @Test
    fun `O24 쿠키 없음 · 만료 · 서명 틀림은 401`() {
        putCache(cacheJson(date.minusDays(1)))
        setClock("${date}T07:30:00+09:00")
        val valid = access("amy0")
        val forged = valid.dropLast(4) + if (valid.endsWith("AAAA")) "BBBB" else "AAAA"
        fun assertUnauthorized(cookie: String?) {
            val result = latest(cookie)
            assertThat(result.status.value()).isEqualTo(401)
            assertThat(result.responseBody).containsEntry("code", "UNAUTHORIZED")
        }

        assertThat(latest(valid).status.value()).isEqualTo(200)
        assertUnauthorized(null)
        assertUnauthorized(forged)
        clock.advance(Duration.ofHours(1).plusSeconds(1)) // access 1시간
        assertUnauthorized(valid)
    }

    // ---- O25 · O26 · O30 — 시각 · 내 순위 ----

    @Test
    fun `O25 자정 뒤에는 전날 결과 — 캐시`() {
        putCache(cacheJson(date))
        setClock("${date.plusDays(1)}T00:30:00+09:00")

        val body = ok("amy0")

        assertThat(body).containsEntry("date", "$date").containsEntry("nextGameStartAt", "${date.plusDays(1)}T07:00:00+09:00")
    }

    @Test
    fun `O25 자정 뒤에는 전날 결과 — PostgreSQL`() {
        val day = LocalDate.of(2026, 10, 23)
        val (groupId, ids) = storedResult(day)
        setClock("2026-10-24T00:30:00+09:00")

        val body = ok(ids[2], groupId)

        assertThat(body).containsEntry("date", "$day").containsEntry("rankingFinal", true).containsEntry("myRank", 3)
            .containsEntry("nextGameStartAt", "2026-10-24T07:00:00+09:00")
    }

    @Test
    fun `O26 다음 게임 시각 — 06시 59분 59초는 오늘 · 07시 · 08시 01분은 내일`() {
        putCache(cacheJson(date.minusDays(1)))
        val cases = mapOf(
            "${date}T06:59:59+09:00" to "${date}T07:00:00+09:00",
            "${date}T07:00:00+09:00" to "${date.plusDays(1)}T07:00:00+09:00",
            "${date}T08:01:00+09:00" to "${date.plusDays(1)}T07:00:00+09:00",
        )
        for ((now, next) in cases) {
            setClock(now)
            assertThat(ok("amy0")["nextGameStartAt"]).describedAs(now).isEqualTo(next)
        }
    }

    @Test
    fun `O30 그날 그룹원이 아닌 유저는 같은 랭킹에 myRank null`() {
        putCache(cacheJson(date.minusDays(1)))
        setClock("${date}T07:30:00+09:00")

        val member = ok("amy0")
        val joiner = ok("late_joiner")

        assertThat(member["myRank"]).isEqualTo(1)
        assertThat(joiner["myRank"]).isNull()
        assertThat(joiner["ranking"]).isEqualTo(member["ranking"])
    }

    /** `findLatest` 호출을 센다 — 결과 조회가 PostgreSQL 을 읽는 하나뿐인 길. */
    class CountingResults(private val delegate: GameResultRepository) : GameResultRepository by delegate {
        val calls = AtomicInteger()

        override suspend fun findLatest(gid: GroupId): LatestResult? {
            calls.incrementAndGet()
            return delegate.findLatest(gid)
        }
    }

    class CountingConfig {
        @Bean
        @Primary
        fun countingResults(@Qualifier("gameResultRepository") delegate: GameResultRepository): CountingResults =
            CountingResults(delegate)
    }

    private companion object {
        const val LATEST = "/api/v1/games/results/latest"
    }
}
