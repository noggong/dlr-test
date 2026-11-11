package com.delightroom.mission.app.api.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 가입 경합 — ORACLE auth-signup I1 · I2 · I3 · I5.
 * 요청마다 코루틴 하나(IO 디스패처)가 시작 래치를 기다렸다가 한꺼번에 보낸다.
 */
class AuthConcurrencyTest : AuthApiTestSupport() {
    /** 같은 래치에서 [bodies] 를 동시에 보내고 (HTTP 상태, code) 목록을 돌려준다. */
    private fun concurrently(bodies: List<Map<String, Any>>): List<Pair<Int, String?>> =
        runBlocking(Dispatchers.IO) {
            val start = CompletableDeferred<Unit>()
            val calls = bodies.map { body ->
                async {
                    start.await()
                    val result = postSignUp(body)
                    result.status.value() to result.responseBody?.get("code") as String?
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
}
