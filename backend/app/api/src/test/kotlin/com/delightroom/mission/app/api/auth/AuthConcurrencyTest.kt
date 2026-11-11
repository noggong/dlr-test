package com.delightroom.mission.app.api.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ScanOptions
import org.springframework.http.HttpHeaders

/**
 * 가입 · 로그인 · refresh 경합 — ORACLE auth-signup I1 ~ I7.
 * 요청마다 코루틴 하나(IO 디스패처)가 시작 래치를 기다렸다가 한꺼번에 보낸다.
 */
class AuthConcurrencyTest : AuthApiTestSupport() {
    /** 같은 래치에서 [bodies] 를 동시에 보내고 (HTTP 상태, code) 목록을 돌려준다. */
    private fun concurrently(bodies: List<Map<String, Any>>): List<Pair<Int, String?>> =
        race(bodies.size) { i -> postSignUp(bodies[i]) }.map { it.status.value() to it.responseBody?.get("code") as String? }

    /** 같은 래치에서 [call] 을 [n] 번 동시에 부르고 결과를 돌려준다. */
    private fun <T> race(n: Int, call: (Int) -> T): List<T> =
        runBlocking(Dispatchers.IO) {
            val start = CompletableDeferred<Unit>()
            val calls = List(n) { i ->
                async {
                    start.await()
                    call(i)
                }
            }
            start.complete(Unit)
            calls.awaitAll()
        }

    private fun body(loginId: String, mode: String, name: String): Map<String, Any> =
        mapOf("loginId" to loginId, "password" to PASSWORD, "group" to mapOf("mode" to mode, "name" to name))

    @Test
    fun `I1 같은 그룹명 동시 생성은 하나만 201`() {
        val name = uniqueGroupName("Race")
        val variants = listOf(name, name.lowercase(), name.uppercase())
        val bodies = List(20) { i -> body(uniqueLoginId(), "NEW", variants[i % variants.size]) }

        val results = concurrently(bodies)

        assertThat(results.count { it.first == 201 }).describedAs("%s", results).isEqualTo(1)
        assertThat(results.filter { it.first != 201 }).hasSize(19).allMatch { it == (409 to "GROUP_NAME_TAKEN") }
        assertThat(queryLong("SELECT count(*) FROM groups WHERE name_key = lower(:name)", "name" to name)).isEqualTo(1)
        assertThat(memberCount(name)).isEqualTo(1)
        assertThat(brokenGroupCount()).isZero()
    }

    @Test
    fun `I2 9명 그룹의 마지막 자리에 10명이 동시에 오면 하나만 201`() {
        val name = uniqueGroupName("Last")
        groupOf(name, 9)
        val bodies = List(10) { body(uniqueLoginId(), "EXISTING", name) }

        val results = concurrently(bodies)

        assertThat(results.count { it.first == 201 }).describedAs("%s", results).isEqualTo(1)
        assertThat(results.filter { it.first != 201 }).hasSize(9).allMatch { it == (409 to "GROUP_FULL") }
        assertThat(memberCount(name)).isEqualTo(10)
        assertThat(userCountOf(name)).isEqualTo(10)
        assertThat(brokenGroupCount()).isZero()
    }

    @Test
    fun `I3 같은 아이디 10개가 각자 새 그룹으로 동시에 오면 하나만 201 이고 새 그룹도 하나만 남는다`() {
        val loginId = uniqueLoginId()
        val names = List(10) { uniqueGroupName("Solo") }
        val groupsBefore = count("groups")

        val results = concurrently(names.map { body(loginId, "NEW", it) })

        assertThat(results.count { it.first == 201 }).describedAs("%s", results).isEqualTo(1)
        assertThat(results.filter { it.first != 201 }).hasSize(9).allMatch { it == (409 to "LOGIN_ID_TAKEN") }
        assertThat(names.count { memberCount(it) != null }).isEqualTo(1)
        assertThat(count("groups") - groupsBefore).isEqualTo(1)
        assertThat(brokenGroupCount()).isZero()
    }

    @Test
    fun `I4 같은 계정 로그인 10개가 동시에 오면 하나만 200 이고 세션은 그 응답의 jti`() {
        val id = newAccount()

        val results = race(10) { postLogin(id) }

        val ok = results.filter { it.status.value() == 200 }
        assertThat(ok).describedAs("%s", results.map { it.status.value() to it.responseBody }).hasSize(1)
        assertThat(results.filter { it.status.value() != 200 })
            .hasSize(9)
            .allMatch { it.status.value() == 409 && it.responseBody!!["code"] == "ALREADY_LOGGED_IN" }
        assertThat(sessionValue(id)).isEqualTo(jtiOf(cookieValue(ok.single(), AuthCookies.REFRESH)))
    }

    @Test
    fun `I6 같은 refresh token 10개가 동시에 오면 하나만 200 이고 진 쪽은 쿠키를 주지 않는다`() {
        val (id, login) = loggedIn()
        val refresh = cookieValue(login, AuthCookies.REFRESH)

        val results = race(10) { postRefresh(refresh) }

        val ok = results.filter { it.status.value() == 200 }
        assertThat(ok).describedAs("%s", results.map { it.status.value() to it.responseBody }).hasSize(1)
        val lost = results.filter { it.status.value() != 200 }
        assertThat(lost).hasSize(9).allMatch { it.status.value() == 401 && it.responseBody!!["code"] == "UNAUTHORIZED" }
        assertThat(lost).allMatch { it.responseHeaders[HttpHeaders.SET_COOKIE].isNullOrEmpty() }
        assertThat(sessionValue(id)).isEqualTo(jtiOf(cookieValue(ok.single(), AuthCookies.REFRESH)))
    }

    @Test
    fun `I7 세션 키는 계정당 하나 · 이름이 sess 숫자 · TTL 은 1~7200`() {
        // 이 JVM 에서 앞서 돈 테스트의 세션과 함께, 로그인 · refresh 를 한 계정을 더 둔다
        val (_, login) = loggedIn()
        postRefresh(cookieValue(login, AuthCookies.REFRESH))

        val keys = runBlocking { redis.scan(ScanOptions.scanOptions().match("sess:*").count(1000).build()).collectList().awaitSingle() }

        assertThat(keys).isNotEmpty().allMatch { Regex("""sess:\{\d+}""").matches(it) }
        val ttls = keys.associateWith { runBlocking { redis.getExpire(it).awaitSingle().seconds } }
        assertThat(ttls.values).describedAs("%s", ttls).allMatch { it in 1..7200 }
    }
}
