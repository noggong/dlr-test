package com.delightroom.mission.app.api.signal

import com.delightroom.mission.app.api.auth.AuthApiTestSupport
import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Range
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * solve-signal API 테스트의 바탕(ORACLE §0) — 지금을 [DATE] 의 어느 KST 시각으로 옮긴다.
 * Lua 는 Redis `TIME` + `game.clock-offset`, 앱은 시계 + 같은 오프셋으로 날짜를 고르므로 시계는 실제 지금에 둔다.
 * 오프셋은 컨텍스트를 만들 때 한 번 계산하고 그 뒤 실제 시간만큼 흐른다 — 경계(07:00 · 08:00)에서 몇 분 띄운다.
 * 명단 · 그룹 Hash 는 LLD §2.1 칸을 직접 `HSET` 한다(game-session 구현 없이). 토큰은 그 그룹원 클레임으로 바로 발급한다.
 */
abstract class SignalApiTestSupport : AuthApiTestSupport() {
    @Autowired
    protected lateinit var tokenIssuer: TokenIssuer

    @Autowired
    protected lateinit var gameProperties: GameProperties

    protected val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    protected val groupKey: String get() = redisKeys.group(gid, DATE)
    protected val rosterKey: String get() = redisKeys.members(gid, DATE)

    protected val json = jacksonObjectMapper()

    @BeforeEach
    fun realNow() = clock.set(Instant.now())

    @AfterEach
    fun cleanUpKeys(): Unit = runBlocking {
        redis.delete(groupKey, rosterKey).awaitSingle()
        Unit
    }

    /** 그룹원 한 줄(LLD §2.1 그룹원 칸). */
    data class Member(
        val uid: Long,
        val lid: String,
        val st: String = "IN_PROGRESS",
        val p: Int = 0,
        val cur: String? = null,
    )

    /** 명단(`#size`)과 그룹 Hash(2명 이상일 때만 — game-session `roster` 와 같다). */
    protected fun group(vararg members: Member) = runBlocking {
        val roster = mapOf("#size" to "${members.size}", "#name" to "g${gid.value}") + members.associate { "${it.uid}" to it.lid }
        redis.opsForHash<String, String>().putAll(rosterKey, roster).awaitSingle()
        if (members.size < 2) return@runBlocking
        val fields = mutableMapOf("date" to "$DATE", "gid" to "${gid.value}", "size" to "${members.size}", "version" to "0")
        members.forEach { m ->
            fields["${m.uid}:lid"] = m.lid
            fields["${m.uid}:st"] = m.st
            fields["${m.uid}:p"] = "${m.p}"
            if (m.st != "NOT_STARTED") {
                fields["${m.uid}:sa"] = "1"
                fields["${m.uid}:late"] = "0"
            }
            m.cur?.let { fields["${m.uid}:cur"] = it }
        }
        redis.opsForHash<String, String>().putAll(groupKey, fields).awaitSingle()
    }

    protected fun hash(): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(groupKey).collectList().awaitSingle().associate { it.key to it.value }
    }

    /** 그 그룹의 그날 명단 Hash(없으면 빈 Map). */
    protected fun roster(group: GroupId = gid): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(redisKeys.members(group, DATE)).collectList().awaitSingle().associate { it.key to it.value }
    }

    protected fun version(): Long = hash()["version"]?.toLong() ?: 0

    /** 이 그룹의 Stream 메시지 `type` — 버킷 Stream 에서 gid 로 거른다. */
    protected fun events(): List<String> = runBlocking {
        val stream = redisKeys.events(gid)
        if (!redis.hasKey(stream).awaitSingle()) return@runBlocking emptyList()
        redis.opsForStream<String, String>().range(stream, Range.unbounded()).collectList().awaitSingle()
            .map { r -> r.value.mapKeys { it.key.toString() }.mapValues { it.value.toString() } }
            .filter { it["gid"] == "${gid.value}" }
            .map { it.getValue("type") }
    }

    /** 그룹 [gid] 의 [uid] 로 access 토큰 — [issuedAt] 을 주면 그 시각에 발급한 것. */
    protected fun access(uid: Long, issuedAt: Instant = gameProperties.now(clock), group: GroupId = gid): String {
        val account = Account(UserId(uid), LoginId("u$uid"), "", group, GroupName("신호"))
        return tokenIssuer.issue(account, "signal-test", issuedAt).access
    }

    protected fun post(path: String, access: String?, body: Any? = null): WebTestClient.ResponseSpec =
        client.post().uri(path)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .let { if (body != null) it.contentType(MediaType.APPLICATION_JSON).bodyValue(body) else it }
            .exchange()

    /** 상태 코드를 확인하고 본문을 Map 으로. */
    protected fun body(spec: WebTestClient.ResponseSpec, status: Int): Map<String, Any?> {
        val raw = spec.expectStatus().isEqualTo(status).expectBody(String::class.java).returnResult().responseBody!!
        return json.readValue(raw)
    }

    protected fun code(spec: WebTestClient.ResponseSpec, status: Int): Any? = body(spec, status)["code"]

    protected fun solveBody(no: Int = 1, qid: String = Q1, clickedAt: String = "${DATE}T07:00:30+09:00") =
        mapOf("questionNo" to no, "questionId" to qid, "successTime" to "${DATE}T07:00:29+09:00", "clickedAt" to clickedAt)

    protected fun passBody(no: Int = 1, qid: String = Q1) = mapOf("questionNo" to no, "questionId" to qid)

    companion object {
        const val SOLVES = "/api/v1/games/today/solves"
        const val PASSES = "/api/v1/games/today/passes"
        const val FAILURES = "/api/v1/games/today/failures"

        val DATE: LocalDate = LocalDate.of(2026, 11, 12)
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        val Q1 = "1".repeat(64)
        val Q2 = "2".repeat(64)

        const val ME = 101L
        const val OTHER = 102L

        /** `game.clock-offset` — 지금을 [DATE] 의 [time] 으로. 컨텍스트를 만들 때 한 번 계산한다. */
        fun offsetTo(registry: DynamicPropertyRegistry, time: LocalTime) {
            registry.add("game.clock-offset") {
                val target = DATE.atTime(time).atZone(ZONE).toInstant()
                Duration.between(Instant.now(), target).toString()
            }
        }
    }
}
