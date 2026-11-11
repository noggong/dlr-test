package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.infra.redis.signal.GroupHashFixture.Member
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ORACLE solve-signal I1 ~ I4 — 같은 슬롯 Lua 직렬 실행으로 반영 · 결과가 한 번뿐인지, D1 시간 창 경계. */
class SignalConcurrencyTest : SignalScriptSupport() {
    private val a = 201L
    private val b = 202L

    @Test
    fun `I1 같은 풀이 신호 100건 동시 — 1회 반영`() {
        val g = Group().write(Member(a, "amy", p = 0, cur = Q1), Member(b, "bob", p = 0))
        g.listen().use { ch ->
            val replies = runBlocking(Dispatchers.Default) {
                (1..100).map { async { g.solveSuspend(a, 1, Q1, at = hms(7, 10)) } }.awaitAll()
            }

            assertThat(replies.count { it.code == "OK" }).isEqualTo(1)
            assertThat(replies.map { it.code }.toSet()).isSubsetOf("OK", "REPLAY")
            assertThat(replies.map { it.body }.toSet()).hasSize(1)
            assertThat(replies.first().progress).isEqualTo(1)
            assertThat(g.field("$a:p")).isEqualTo("1")
            assertThat(g.version()).isEqualTo(1)
            assertThat(g.events().count { it["type"] == "solve.applied" }).isEqualTo(1)
            assertThat(ch.settle(1)).hasSize(1)
        }
    }

    @Test
    fun `I2 그룹 마지막 두 명이 동시에 끝내도 결과는 한 번 — 200회`() {
        repeat(200) { i ->
            val g = Group().write(
                Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)),
                Member(b, "bob", p = 2, cur = Q3, ls = hms(7, 31)),
            )
            val (ra, rb) = runBlocking(Dispatchers.Default) {
                listOf(async { g.solveSuspend(a, 3, Q3, at = hms(7, 40)) }, async { g.solveSuspend(b, 3, Q3, at = hms(7, 40)) }).awaitAll()
            }

            val h = g.hash()
            assertThat(h["result"]).`as`("반복 $i").isEqualTo("SUCCEEDED")
            assertThat(g.events().count { it["type"] == "group.decided" }).`as`("반복 $i").isEqualTo(1)
            assertThat(listOf(ra.code, rb.code)).containsOnly("OK")
            val second = listOf(ra, rb).maxBy { it.version }
            assertThat(second.groupStatus).`as`("반복 $i — 두 번째 실행").isEqualTo("SUCCEEDED")
            assertThat(listOf(h["$a:st"], h["$b:st"])).containsOnly("SUCCEEDED")
        }
    }

    @Test
    fun `I3 도착 08_00_02_9 · 클릭 07_59_59_9 는 반영 — 20회`() {
        repeat(20) { i ->
            val g = Group().write(Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)), Member(b, "bob", p = 0))
            val r = g.solve(a, 3, Q3, at = hms(8, 0, 2, 900), clickedAt = hms(7, 59, 59, 900))

            assertThat(r.code).`as`("반복 $i").isEqualTo("OK")
            assertThat(g.field("$a:p")).isEqualTo("3")
            assertThat(r.t).isBetween(hms(8, 0, 2, 900), hms(8, 0, 2, 999))
            assertThat(g.events().single()["grace"]).isEqualTo("1")
        }
    }

    @Test
    fun `I4 도착 08_00_03_0 은 거부 — 20회`() {
        repeat(20) { i ->
            val g = Group().write(Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)), Member(b, "bob", p = 0))
            val r = g.solve(a, 3, Q3, at = hms(8, 0, 3, 0), clickedAt = hms(7, 59, 59, 900))

            assertThat(r.code).`as`("반복 $i").isEqualTo("SUBMISSION_CLOSED")
            assertThat(g.field("$a:p")).isEqualTo("2")
            assertThat(g.version()).isZero()
            assertThat(g.events()).isEmpty()
        }
    }
}
