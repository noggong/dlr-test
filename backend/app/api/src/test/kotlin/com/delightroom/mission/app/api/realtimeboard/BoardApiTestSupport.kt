package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.app.api.auth.AuthApiTestSupport
import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import com.delightroom.mission.core.testfixture.BoardFixtures
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.random.Random

/**
 * realtime-board API 테스트의 바탕 — 지금을 [BoardFixtures.DATE] 의 어느 KST 시각으로 옮긴다(ORACLE 테스트 규약).
 * Lua 는 Redis `TIME` + `game.clock-offset`, 앱은 시계 + 같은 오프셋으로 날짜를 고르므로 시계는 실제 지금에 둔다.
 * 그룹 Hash · 명단은 SPEC §3 의 4명 그룹을 `HSET` 한다. 토큰은 그 그룹원 클레임으로 바로 발급한다(가입 · 로그인을 거치지 않는다).
 */
abstract class BoardApiTestSupport : AuthApiTestSupport() {
    @Autowired
    protected lateinit var tokenIssuer: TokenIssuer

    @Autowired
    protected lateinit var gameProperties: GameProperties

    protected val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    protected val groupKey: String get() = redisKeys.group(gid, BoardFixtures.DATE)
    protected val rosterKey: String get() = redisKeys.members(gid, BoardFixtures.DATE)

    @BeforeEach
    fun realNow() = clock.set(Instant.now())

    @AfterEach
    fun cleanUpKeys(): Unit = runBlocking {
        redis.delete(groupKey, rosterKey).awaitSingle()
        Unit
    }

    protected fun hset(key: String, fields: Map<String, String>) = runBlocking {
        redis.opsForHash<String, String>().putAll(key, fields).awaitSingle()
    }

    /** 명단 Hash — `#size` 와 uid → 로그인 아이디. */
    protected fun roster(members: Map<Long, String>) =
        hset(rosterKey, linkedMapOf("#size" to "${members.size}", "#name" to "g$gid") + members.mapKeys { "${it.key}" })

    /** SPEC §3 의 4명 그룹 — 명단 + 결과 확정 전 그룹 Hash. */
    protected fun fourMembers() {
        roster(MEMBERS)
        hset(groupKey, BoardFixtures.inProgressHash())
    }

    /** 그룹 [gid] 의 [uid] 로 access 토큰 — [issuedAt] 을 주면 그 시각에 발급한 것. */
    protected fun access(uid: Long, issuedAt: Instant = gameProperties.now(clock)): String {
        val login = MEMBERS[uid] ?: "outsider"
        val account = Account(UserId(uid), LoginId(login), "", gid, GroupName("현황판"))
        return tokenIssuer.issue(account, "board-test", issuedAt).access
    }

    protected fun getBoard(access: String?): WebTestClient.ResponseSpec =
        client.get().uri(BOARD)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .exchange()

    companion object {
        const val BOARD = "/api/v1/games/today/board"

        val MEMBERS = linkedMapOf(
            BoardFixtures.JUNHO to "junho",
            BoardFixtures.MINJI to "minji",
            BoardFixtures.DOHYUN to "dohyun",
            BoardFixtures.HANA to "hana",
        )

        /** `game.clock-offset` — 지금을 [BoardFixtures.DATE] 의 [time] 으로. 컨텍스트를 만들 때 한 번 계산한다. */
        fun offsetTo(registry: DynamicPropertyRegistry, time: LocalTime) {
            registry.add("game.clock-offset") {
                val target = BoardFixtures.DATE.atTime(time).atZone(BoardFixtures.ZONE).toInstant()
                Duration.between(Instant.now(), target).toString()
            }
        }
    }
}
