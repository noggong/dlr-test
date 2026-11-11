package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.infra.redis.signal.GroupHashFixture.Member
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** ORACLE solve-signal I1 ~ I8 — 같은 슬롯 Lua 직렬 실행으로 반영 · 결과가 한 번뿐인지, D1 시간 창 경계, 무작위 신호 열의 불변식. */
class SignalConcurrencyTest : SignalScriptSupport() {
    private val a = 201L
    private val b = 202L
    private val c = 203L

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

    @Test
    fun `I5 I6 무작위 신호 1,000개 — 진행도 비감소 · 완료 상태 고정 · 반영마다 version +1 · 이벤트 +1`() {
        val rnd = Random(20261112)
        var qseq = 0
        fun freshQ() = "%064x".format(++qseq)
        val window = hms(6, 59) until hms(8, 0, 5)
        val done = setOf("SUCCEEDED", "CHEAT_FAILED")
        val applied = mapOf("solve" to "solve.applied", "pass" to "pass.used", "fail" to "member.cheat_failed")
        var signals = 0
        var oks = 0
        var decided = 0

        repeat(25) { round ->
            val cStarted = rnd.nextBoolean()
            val g = Group().write(
                Member(a, "amy", p = 0, cur = freshQ()),
                Member(b, "bob", p = 0, cur = freshQ()),
                if (cStarted) Member(c, "cy", p = 0, cur = freshQ()) else Member(c, "cy", st = "NOT_STARTED", p = -1),
            )
            val sent = mutableListOf<Triple<String, Long, Pair<Int, String>>>() // 종류 · uid · (번호, 문제)
            repeat(40) { step ->
                val before = g.hash()
                val uid = listOf(a, b, c).random(rnd)
                // game-session 의 `shown` 흉내 — 지금 문제가 비었으면 다음 번호의 새 문제를 채운다(신호가 아니라 version · Stream 은 그대로).
                if (before["$uid:cur"] == null && before["$uid:p"]!!.toInt() in 0..2 && before["$uid:st"] == "IN_PROGRESS" && rnd.nextInt(10) < 7) {
                    g.set("$uid:cur" to freshQ())
                }
                val h0 = g.hash()
                val e0 = g.events().size
                val at = window.first + rnd.nextLong(window.last - window.first)
                val signal = if (sent.isNotEmpty() && rnd.nextInt(5) == 0) {
                    sent.random(rnd) // 재시도 — 같은 본문을 다른 시각에
                } else {
                    // 반영 갈래를 충분히 지나도록 다음 번호(진행도 + 1)를 자주 고른다. 실패 신호는 그룹원을 끝내므로 드물게.
                    val kind = listOf("solve", "solve", "solve", "solve", "pass", "pass", "fail").random(rnd)
                    val next = (h0["$uid:p"]!!.toInt() + 1).coerceIn(1, 3)
                    val no = if (rnd.nextInt(10) < 7) next else rnd.nextInt(1, 4)
                    val qid = h0["$uid:cur"]?.takeIf { rnd.nextInt(4) != 0 } ?: QX
                    Triple(kind, uid, no to qid)
                }
                sent += signal
                val (kind, who, nq) = signal
                val (code, rp) = when (kind) {
                    "solve" -> g.solve(who, nq.first, nq.second, at = at, clickedAt = at - rnd.nextLong(1_000_000)).let { it.code to it.progress }
                    "pass" -> g.pass(who, nq.first, nq.second, at = at).let { it.code to it.reply.progress }
                    else -> g.fail(who, at = at).let { it.code to it.progress }
                }
                val h1 = g.hash()
                val label = "라운드 $round · 단계 $step · $kind($who, $nq) → $code"
                signals++

                listOf(a, b, c).forEach { u ->
                    val p0 = h0["$u:p"]!!.toInt()
                    val p1 = h1["$u:p"]!!.toInt()
                    assertThat(p1).`as`("$label — $u 진행도 비감소").isGreaterThanOrEqualTo(p0).isBetween(-1, 3)
                    if (h0["$u:st"] in done) assertThat(h1["$u:st"]).`as`("$label — $u 완료 상태 고정").isEqualTo(h0["$u:st"])
                }
                assertThat(rp).`as`("$label — 응답 진행도").isEqualTo(h1["$who:p"]!!.toInt())

                val dv = h1["version"]!!.toLong() - h0["version"]!!.toLong()
                val added = g.events().drop(e0).map { it["type"] }
                if (code == "OK") {
                    oks++
                    val wrote = h0["result"] == null && h1["result"] != null
                    if (wrote) decided++
                    assertThat(dv).`as`("$label — version").isEqualTo(1)
                    assertThat(added).`as`("$label — 이벤트")
                        .isEqualTo(listOf(applied[kind]) + if (wrote) listOf("group.decided") else emptyList())
                } else {
                    assertThat(dv).`as`("$label — version").isZero()
                    assertThat(added).`as`("$label — 이벤트").isEmpty()
                    assertThat(h1).`as`("$label — Hash 그대로").isEqualTo(h0)
                }
            }
        }

        assertThat(signals).isEqualTo(1_000)
        // 열이 판정 갈래를 고르게 지났는지 — 반영 · 결과 기록이 실제로 일어났어야 위 단언이 뜻이 있다.
        assertThat(oks).isGreaterThan(100)
        assertThat(decided).isGreaterThan(0)
    }

    @Test
    fun `I7 같은 패스 100건 동시 — 1회 반영`() {
        val g = Group().write(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)), Member(b, "bob", p = 0))
        g.listen().use { ch ->
            val replies = runBlocking(Dispatchers.Default) {
                (1..100).map { async { g.passSuspend(a, 2, Q2, at = hms(7, 20)) } }.awaitAll()
            }

            assertThat(replies.count { it.code == "OK" }).isEqualTo(1)
            assertThat(replies.map { it.code }.toSet()).isSubsetOf("OK", "REPLAY")
            assertThat(replies.map { it.body }.toSet()).hasSize(1)
            assertThat(replies.first().passedAtQuestionNo).isEqualTo(2)
            assertThat(g.field("$a:pn")).isEqualTo("2")
            assertThat(g.version()).isEqualTo(1)
            assertThat(g.events().count { it["type"] == "pass.used" }).isEqualTo(1)
            assertThat(ch.settle(1)).hasSize(1)
        }
    }

    @Test
    fun `I8 부정 실패와 마지막 풀이가 동시에 와도 결과는 한 번 — 200회`() {
        repeat(200) { i ->
            val g = Group().write(
                Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)),
                Member(b, "bob", p = 1, ls = hms(7, 20)),
            )
            val (ra, rb) = runBlocking(Dispatchers.Default) {
                listOf(async { g.solveSuspend(a, 3, Q3, at = hms(7, 40)) }, async { g.failSuspend(b, at = hms(7, 40)) }).awaitAll()
            }

            val h = g.hash()
            assertThat(h["result"]).`as`("반복 $i").isEqualTo("FAILED_IMMEDIATE")
            assertThat(g.events().count { it["type"] == "group.decided" }).`as`("반복 $i").isEqualTo(1)
            assertThat(listOf(ra.code, rb.code)).containsOnly("OK")
            assertThat(listOf(h["$a:st"], h["$b:st"])).containsExactly("SUCCEEDED", "CHEAT_FAILED")
            assertThat(listOf(ra, rb).maxBy { it.version }.groupStatus).`as`("반복 $i — 두 번째 실행").isEqualTo("FAILED")
        }
    }
}
