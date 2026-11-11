package com.delightroom.mission.app.api.gamesession

import com.delightroom.mission.app.api.auth.AuthApiTestSupport
import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.app.api.auth.JwtTokenIssuer
import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORACLE game-session O1 ~ O4 — 게임 정보 `GET /api/v1/games/today`(SPEC §2.1 · GS1 · GS3 · LLD §3.1 · §3.4).
 *
 * 고정 데이터(ORACLE §0): `D = 2026-11-12`, 그룹 A(06:00 가입 alice · bob · carol + 07:30 가입 late) · 그룹 S(06:00 가입 1명).
 * 테스트끼리 DB · Redis 를 비우지 않으므로 그룹 · 아이디는 매번 새로 만든다(아이디 꼬리가 같아 alice < bob < carol 순서는 그대로).
 * 서버 시각은 `MutableClock` 으로 옮긴다 — 게임 정보는 Lua 를 부르지 않아 앱 시각(`clock + game.clock-offset`)만 본다.
 */
class GameInfoTest : AuthApiTestSupport() {
    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var gameProperties: GameProperties

    private class Member(val id: UserId, val loginId: String)

    private class Fixture(val gid: GroupId, val name: String, val members: Map<String, Member>) {
        operator fun get(prefix: String): Member = members.getValue(prefix)
    }

    @BeforeEach
    fun partitions() = runBlocking {
        // 하루 파티션은 기동 때 KST 오늘 · 내일만 생긴다 — 고정 날짜 D 는 직접 만든다(Q10 -1 행)
        for (d in listOf(D, D.plusDays(1))) {
            val suffix = d.toString().replace("-", "")
            db.sql(
                "CREATE TABLE IF NOT EXISTS daily_progress_$suffix PARTITION OF daily_progress " +
                    "FOR VALUES FROM ('$d') TO ('${d.plusDays(1)}')",
            ).then().awaitSingleOrNull()
        }
    }

    /** 그룹 하나와 그룹원 — (아이디 앞부분, 가입 시각). 가입 API 는 `joined_at = now()` 라 행을 직접 넣는다. */
    private fun group(vararg joins: Pair<String, String>): Fixture = runBlocking {
        val tag = "${RUN}${SEQ.incrementAndGet()}"
        val name = "Info-$tag"
        val gid = db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, lower(:n), :c) RETURNING id")
            .bind("n", name).bind("c", joins.size)
            .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle()
        // 넣는 순서를 이름 순서와 다르게 한다 — 응답 정렬이 넣은 순서에 기대지 않는지 본다
        val members = joins.reversed().associate { (prefix, joinedAt) ->
            val loginId = "${prefix}_$tag"
            val id = db.sql(
                "INSERT INTO users (login_id, password_hash, group_id, joined_at) VALUES (:l, 'x', :g, :j) RETURNING id",
            )
                .bind("l", loginId).bind("g", gid).bind("j", OffsetDateTime.parse(joinedAt))
                .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle()
            prefix to Member(UserId(id), loginId)
        }
        Fixture(GroupId(gid), name, members)
    }

    private fun groupA() = group(
        "alice" to "${D}T06:00:00+09:00",
        "bob" to "${D}T06:00:00+09:00",
        "carol" to "${D}T06:00:00+09:00",
        "late" to "${D}T06:55:00+09:00", // 06:50 부터 가입 — 그날 그룹원 아님(prd D24)
    )

    /** 지금 시계로 발급한 access 토큰 — 시계를 옮긴 뒤 부른다(만료 15분). */
    private fun access(fixture: Fixture, member: Member): String =
        tokenIssuer.issue(
            Account(member.id, LoginId(member.loginId), "", fixture.gid, GroupName(fixture.name)),
            "jti-${member.loginId}",
            gameProperties.now(clock),
        ).access

    private fun gameInfo(access: String?): EntityExchangeResult<Map<String, Any>> =
        client.get().uri(GAME_INFO)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .exchange().expectBody(MAP_TYPE).returnResult()

    @Suppress("UNCHECKED_CAST")
    private fun memberIds(body: Map<String, Any>): List<String> =
        ((body["group"] as Map<String, Any>)["members"] as List<Map<String, Any>>).map { it["userId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun laterIds(body: Map<String, Any>): List<String> =
        ((body["group"] as Map<String, Any>)["laterMembers"] as List<Map<String, Any>>).map { it["userId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun groupName(body: Map<String, Any>): String = (body["group"] as Map<String, Any>)["name"] as String

    private fun instant(body: Map<String, Any>, field: String) = OffsetDateTime.parse(body[field] as String).toInstant()

    /** 이 기능이 만드는 키(명단 · 그룹 Hash · 그날 그룹 목록 · 은행) 수 — 다른 테스트의 TTL 키 만료에 흔들리지 않게 좁힌다. */
    private fun gameKeyCount(): Long = runBlocking {
        listOf("g:*", "day:*", "bank:*").sumOf { redis.keys(it).count().awaitSingle() }
    }

    private fun hasKey(key: String): Boolean = runBlocking { redis.hasKey(key).awaitSingle() }

    private fun rosterHash(fixture: Fixture): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(redisKeys.members(fixture.gid, D))
            .collectMap({ it.key }, { it.value }).awaitSingle()
    }

    @Test
    fun `O1 07시 전 게임 정보는 지금까지의 06시50분 전 가입자 · 명단 키를 만들지 않는다`(): Unit = runBlocking {
        val a = groupA()
        setClock("${D}T06:55:00+09:00")

        val result = gameInfo(access(a, a["alice"]))

        assertThat(result.status.value()).isEqualTo(200)
        val body = result.responseBody!!
        assertThat(body["date"]).isEqualTo(D.toString())
        assertThat(body["startAt"]).isEqualTo("${D}T07:00:00+09:00")
        assertThat(body["deadlineAt"]).isEqualTo("${D}T08:00:00+09:00")
        assertThat(memberIds(body)).containsExactly(a["alice"].loginId, a["bob"].loginId, a["carol"].loginId)
        assertThat(groupName(body)).isEqualTo(a.name)
        val now = gameProperties.now(clock)
        assertThat(Duration.between(instant(body, "serverTime"), now).abs()).isLessThanOrEqualTo(Duration.ofSeconds(1))
        assertThat(body["serverTime"] as String).endsWith("+09:00")
        assertThat(hasKey(redisKeys.members(a.gid, D))).isFalse()
        assertThat(hasKey(redisKeys.group(a.gid, D))).isFalse()
    }

    @Test
    fun `O2 07시 뒤 게임 정보는 확정 명단 · 없으면 그 자리에서 만든다 — 그날 그룹원이 아닌 가입자에게도 같은 명단`(): Unit = runBlocking {
        val a = groupA()
        setClock("${D}T07:40:00+09:00")
        assertThat(hasKey(redisKeys.members(a.gid, D))).isFalse()

        val expected = listOf(a["alice"].loginId, a["bob"].loginId, a["carol"].loginId)
        for (who in listOf("alice", "late")) {
            val result = gameInfo(access(a, a[who]))
            assertThat(result.status.value()).describedAs(who).isEqualTo(200)
            assertThat(result.responseBody!!["date"]).isEqualTo(D.toString())
            assertThat(memberIds(result.responseBody!!)).describedAs(who).containsExactlyElementsOf(expected)
        }

        val roster = rosterHash(a)
        assertThat(roster["#size"]).isEqualTo("3")
        assertThat(roster["#name"]).isEqualTo(a.name)
        assertThat(roster.filterKeys { !it.startsWith('#') })
            .isEqualTo(listOf("alice", "bob", "carol").associate { a[it].id.value.toString() to a[it].loginId })
        assertThat(hasKey(redisKeys.group(a.gid, D))).isTrue()
        // 만료 = 게임 시각으로 다음 날 08:00(D 07:40 → 24시간 20분 — LLD v5 §4.1)
        assertThat(redis.getExpire(redisKeys.members(a.gid, D)).awaitSingle())
            .isBetween(Duration.ofHours(24).plusMinutes(19).plusSeconds(58), Duration.ofHours(24).plusMinutes(20))
        // LLD §3.4-3 — 만든 쪽이 그날 -1 행을 넣는다
        assertThat(
            queryLong("SELECT count(*) FROM daily_progress WHERE game_date = :d AND group_id = :g AND progress = -1", "d" to D, "g" to a.gid.value),
        ).isEqualTo(3)
    }

    @Test
    fun `O2 확정 명단이 있으면 그것을 준다 — 그 뒤 PostgreSQL 이 바뀌어도 그날 명단은 그대로`(): Unit = runBlocking {
        val a = groupA()
        setClock("${D}T07:05:00+09:00")
        gameInfo(access(a, a["alice"]))
        // 06:50 전 가입 시각으로 한 명이 더 생겨도(운영상 없음) 확정 명단은 바뀌지 않는다(GS3 — 07:00 에 확정)
        db.sql("UPDATE users SET joined_at = :j WHERE id = :id")
            .bind("j", OffsetDateTime.parse("${D}T06:30:00+09:00")).bind("id", a["late"].id.value)
            .then().awaitSingleOrNull()

        val body = gameInfo(access(a, a["bob"])).responseBody!!

        assertThat(memberIds(body)).containsExactly(a["alice"].loginId, a["bob"].loginId, a["carol"].loginId)
        assertThat(rosterHash(a)["#size"]).isEqualTo("3")
    }

    @Test
    fun `O2 1명 그룹도 07시 뒤 명단을 만들고 members 는 1개 · 그룹 Hash 와 그날 그룹 목록에는 없다`(): Unit = runBlocking {
        val s = group("solo" to "${D}T06:00:00+09:00")
        setClock("${D}T07:10:00+09:00")

        val result = gameInfo(access(s, s["solo"]))

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(memberIds(result.responseBody!!)).containsExactly(s["solo"].loginId)
        assertThat(rosterHash(s)["#size"]).isEqualTo("1")
        assertThat(hasKey(redisKeys.group(s.gid, D))).isFalse()
        val day = redisKeys.dayGroups(redisKeys.bucket(s.gid), D)
        assertThat(redis.opsForSet().isMember(day, s.gid.value.toString()).awaitSingle()).isFalse()
    }

    @Test
    fun `O1 07시 경계 — 06시59분59초999 는 명단을 만들지 않고 07시00분00초000 은 만든다`(): Unit = runBlocking {
        val a = groupA()

        setClock("${D}T06:59:59.999+09:00")
        gameInfo(access(a, a["alice"]))
        assertThat(hasKey(redisKeys.members(a.gid, D))).isFalse()

        setClock("${D}T07:00:00.000+09:00")
        gameInfo(access(a, a["alice"]))
        assertThat(rosterHash(a)["#size"]).isEqualTo("3")
    }

    @Test
    fun `O36 06시50분 정각 가입자는 그날 그룹원이 아니다 — 06시49분59초999999 는 들고 06시50분00초는 빠진다`(): Unit = runBlocking {
        val g = group(
            "alice" to "${D}T06:49:59.999999+09:00",
            "bob" to "${D}T06:00:00+09:00",
            "edge" to "${D}T06:50:00+09:00",
        )
        val expected = listOf(g["alice"].loginId, g["bob"].loginId)

        setClock("${D}T06:55:00+09:00")
        assertThat(memberIds(gameInfo(access(g, g["alice"])).responseBody!!)).containsExactlyElementsOf(expected)
        setClock("${D}T07:01:00+09:00")
        assertThat(memberIds(gameInfo(access(g, g["edge"])).responseBody!!)).containsExactlyElementsOf(expected)
        assertThat(rosterHash(g)["#size"]).isEqualTo("2")
    }

    @Test
    fun `O3 08시를 넘으면 다음 날 게임 — 07시59분59초999 는 D · 08시 정각은 D+1`(): Unit = runBlocking {
        val a = groupA()

        setClock("${D}T07:59:59.999+09:00")
        val before = gameInfo(access(a, a["alice"])).responseBody!!
        setClock("${D}T08:00:00.000+09:00")
        val after = gameInfo(access(a, a["alice"])).responseBody!!

        assertThat(before["date"]).isEqualTo(D.toString())
        assertThat(before["serverTime"]).isEqualTo("${D}T07:59:59.999+09:00")
        assertThat(after["date"]).isEqualTo(D.plusDays(1).toString())
        assertThat(after["startAt"]).isEqualTo("${D.plusDays(1)}T07:00:00+09:00")
        assertThat(after["deadlineAt"]).isEqualTo("${D.plusDays(1)}T08:00:00+09:00")
        // D+1 은 아직 07:00 전 — 명단 키를 만들지 않고 07:00 전 가입자(지금은 late 포함 4명)를 준다
        assertThat(memberIds(after)).containsExactly(a["alice"].loginId, a["bob"].loginId, a["carol"].loginId, a["late"].loginId)
        assertThat(laterIds(after)).isEmpty()
        assertThat(hasKey(redisKeys.members(a.gid, D.plusDays(1)))).isFalse()
    }

    @Test
    fun `O38 늦은 그룹원은 laterMembers 로만 보인다 — 07시 전 · 뒤 모두, 명단에는 들지 않는다`(): Unit = runBlocking {
        val a = groupA()
        setClock("${D}T06:55:00+09:00")
        val early = gameInfo(access(a, a["alice"])).responseBody!!
        assertThat(memberIds(early)).containsExactly(a["alice"].loginId, a["bob"].loginId, a["carol"].loginId)
        assertThat(laterIds(early)).containsExactly(a["late"].loginId)

        setClock("${D}T07:40:00+09:00")
        for (who in listOf("alice", "late")) {
            val body = gameInfo(access(a, a[who])).responseBody!!
            assertThat(memberIds(body)).describedAs(who).containsExactly(a["alice"].loginId, a["bob"].loginId, a["carol"].loginId)
            assertThat(laterIds(body)).describedAs(who).containsExactly(a["late"].loginId)
        }
        val roster = rosterHash(a)
        assertThat(roster["#size"]).isEqualTo("3")
        assertThat(roster).doesNotContainKey(a["late"].id.value.toString())
    }

    @Test
    fun `O38 1명 그룹 판정은 members 기준 — 늦은 그룹원이 있어도 members 1개 · 없으면 laterMembers 는 빈 배열`(): Unit = runBlocking {
        val s = group("solo" to "${D}T06:00:00+09:00", "late" to "${D}T06:55:00+09:00")
        val only = group("only" to "${D}T06:00:00+09:00")
        setClock("${D}T07:10:00+09:00")

        val body = gameInfo(access(s, s["solo"])).responseBody!!
        assertThat(memberIds(body)).containsExactly(s["solo"].loginId)
        assertThat(laterIds(body)).containsExactly(s["late"].loginId)
        assertThat(rosterHash(s)["#size"]).isEqualTo("1")
        assertThat(hasKey(redisKeys.group(s.gid, D))).isFalse()

        assertThat(laterIds(gameInfo(access(only, only["only"])).responseBody!!)).isEmpty()
    }

    @Test
    fun `O4 인증 없으면 401 — 세 엔드포인트 × 쿠키 없음 · 서명이 틀린 JWT, Redis 키 수 변화 0`(): Unit = runBlocking {
        val a = groupA()
        setClock("${D}T07:20:00+09:00")
        val forged = JwtTokenIssuer(AuthProperties(jwtSecret = "another-secret-that-is-32-bytes-long!!"))
            .issue(Account(a["alice"].id, LoginId(a["alice"].loginId), "", a.gid, GroupName(a.name)), "jti", gameProperties.now(clock))
            .access
        val before = gameKeyCount()

        val calls = listOf(HttpMethod.GET to GAME_INFO, HttpMethod.POST to QUESTION_BANK, HttpMethod.POST to SHOWN)
        for ((method, path) in calls) {
            for (access in listOf(null, forged)) {
                client.method(method).uri(path)
                    .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
                    .apply { if (path == SHOWN) contentType(MediaType.APPLICATION_JSON).bodyValue(SHOWN_BODY) }
                    .exchange()
                    .expectStatus().isEqualTo(401)
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
            }
        }

        assertThat(gameKeyCount()).isEqualTo(before)
        assertThat(hasKey(redisKeys.members(a.gid, D))).isFalse()
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        const val GAME_INFO = "/api/v1/games/today"
        const val QUESTION_BANK = "/api/v1/games/today/question-bank"
        const val SHOWN = "/api/v1/games/today/shown"
        val SHOWN_BODY = mapOf("questionNo" to 1, "questionId" to "a".repeat(64))

        val SEQ = AtomicInteger()
        val RUN: String = Math.floorMod(System.nanoTime(), 46_656L).toString(36).padStart(3, '0')
    }
}
