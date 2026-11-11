package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.infra.redis.signal.GroupHashFixture.Member
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** ORACLE solve-signal O23 ~ O26 · O28② · O6 ~ O8 (Lua 수준) — Lua `fail` 반영 · D9 · D17 무시 · 그룹 판정. */
class FailScriptTest : SignalScriptSupport() {
    private val json = jacksonObjectMapper()

    private val a = 401L
    private val b = 402L

    private fun Group.twoMembers(me: Member, group: Map<String, String> = emptyMap()) =
        write(me, Member(b, "bob", st = "IN_PROGRESS", p = 0), group = group)

    @Test
    fun `O23 부정 실패 반영 — 상태 · 실패 시각 · 진행도 그대로 · Stream · 채널`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        g.listen().use { ch ->
            val r = g.fail(a, at = hms(7, 30))

            assertThat(r.code).isEqualTo("OK")
            assertThat(listOf(r.progress, r.status, r.groupStatus)).containsExactly(1, "CHEAT_FAILED", "IN_PROGRESS")
            assertThat(r.version).isEqualTo(1)
            val h = g.hash()
            assertThat(h["$a:st"]).isEqualTo("CHEAT_FAILED")
            assertThat(h["$a:cf"]).isEqualTo(r.t.toString())
            assertThat(h["$a:p"]).isEqualTo("1")
            assertThat(h["$a:ls"]).isEqualTo(hms(7, 10).toString())
            assertThat(h).doesNotContainKey("result")

            val ev = g.events().single()
            assertThat(ev).containsAllEntriesOf(
                mapOf(
                    "type" to "member.cheat_failed", "date" to "2026-11-12", "gid" to g.gid.toString(), "uid" to "$a", "lid" to "amy",
                    "version" to "1", "at" to r.t.toString(), "progress" to "1", "cheatFailedAt" to r.t.toString(),
                ),
            )

            val msg: Map<String, Any?> = json.readValue(ch.settle(1).single())
            assertThat(msg["type"]).isEqualTo("member.cheat_failed")
            assertThat(msg["version"]).isEqualTo(1)
            @Suppress("UNCHECKED_CAST")
            assertThat(msg["hash"] as Map<String, String>).isEqualTo(h)
        }
    }

    @Test
    fun `O23 시간 창 경계 — 07_59_59_900 도착은 반영 · 지금 문제는 그대로`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        val r = g.fail(a, at = hms(7, 59, 59, 900))

        assertThat(listOf(r.code, r.status)).containsExactly("OK", "CHEAT_FAILED")
        assertThat(r.t).isLessThan(deadlineAt)
        assertThat(g.field("$a:cur")).isEqualTo(Q2)
    }

    @Test
    fun `O24 미션 성공 뒤 실패 신호는 무시 — D9`() {
        val g = Group().twoMembers(Member(a, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)))
        g.listen().use { ch ->
            val r = g.fail(a, at = hms(7, 40))

            assertThat(listOf(r.code, r.progress, r.status, r.groupStatus)).containsExactly("REPLAY", 3, "SUCCEEDED", "IN_PROGRESS")
            assertThat(g.field("$a:st")).isEqualTo("SUCCEEDED")
            assertThat(g.version()).isZero()
            assertThat(g.events()).isEmpty()
            assertThat(ch.settle(0)).isEmpty()
        }
    }

    @Test
    fun `O25 실패 신호 재시도는 같은 본문 · 무반영`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        val first = g.fail(a, at = hms(7, 30))
        g.listen().use { ch ->
            val again = g.fail(a, at = hms(7, 31))

            assertThat(again.code).isEqualTo("REPLAY")
            assertThat(again.body).isEqualTo(first.body)
            assertThat(g.field("$a:cf")).isEqualTo(first.t.toString())
            assertThat(g.version()).isEqualTo(1)
            assertThat(g.events()).hasSize(1)
            assertThat(ch.settle(0)).isEmpty()
        }
    }

    @Test
    fun `O26 08_00 이후 · 결과 확정 뒤 실패 신호는 무시 — D17`() {
        val pending = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        val r1 = pending.fail(a, at = hms(8, 0, 0, 100))
        assertThat(listOf(r1.code, r1.status, r1.groupStatus)).containsExactly("REPLAY", "IN_PROGRESS", "PENDING")
        assertThat(pending.field("$a:st")).isEqualTo("IN_PROGRESS")

        val failed = Group().write(
            Member(a, "amy", st = "FAILED", p = 1, ls = hms(7, 10)),
            Member(b, "bob", st = "FAILED", p = 0),
            group = mapOf("result" to "FAILED_DEADLINE", "rankingFinal" to "1"),
        )
        val r2 = failed.fail(a, at = hms(8, 0, 10))
        assertThat(listOf(r2.code, r2.status, r2.groupStatus)).containsExactly("REPLAY", "FAILED", "FAILED")

        assertThat(pending.version() + failed.version()).isZero()
        assertThat(pending.events() + failed.events()).isEmpty()
    }

    @Test
    fun `O26 결과가 난 그룹은 08_00 전이어도 실패 신호 무시`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)), group = mapOf("result" to "FAILED_IMMEDIATE"))
        val r = g.fail(a, at = hms(7, 50))

        assertThat(listOf(r.code, r.status, r.groupStatus)).containsExactly("REPLAY", "IN_PROGRESS", "FAILED")
        assertThat(g.version()).isZero()
    }

    @Test
    fun `O28 부정 실패로 전원 완료 — 즉시 실패를 한 번 기록 · 전달`() {
        val g = Group().write(
            Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)),
            Member(b, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
        )
        g.listen().use { ch ->
            val r = g.fail(a, at = hms(7, 40))

            assertThat(listOf(r.code, r.status, r.groupStatus)).containsExactly("OK", "CHEAT_FAILED", "FAILED")
            val h = g.hash()
            assertThat(h["result"]).isEqualTo("FAILED_IMMEDIATE")
            assertThat(h["decidedAt"]).isEqualTo(r.t.toString())
            assertThat(h["rankingFinal"]).isEqualTo("1")
            assertThat(h["version"]).isEqualTo("1")

            val events = g.events()
            assertThat(events.map { it["type"] }).containsExactly("member.cheat_failed", "group.decided")
            val decided = events[1]
            assertThat(decided).containsAllEntriesOf(
                mapOf(
                    "uid" to "$a", "lid" to "amy", "version" to "1", "at" to r.t.toString(), "result" to "FAILED_IMMEDIATE",
                    "decidedAt" to r.t.toString(), "memberCount" to "2", "rankingFinal" to "1",
                ),
            )
            val ranking: List<Map<String, Any?>> = json.readValue(decided["ranking"]!!)
            assertThat(ranking.map { Triple(it["u"], it["r"], it["s"]) })
                .containsExactly(Triple("bob", 1, "SUCCEEDED"), Triple("amy", 2, "CHEAT_FAILED"))
            assertThat(json.readValue<List<Map<String, Any?>>>(h["ranking"]!!)).isEqualTo(ranking)

            val msg: Map<String, Any?> = json.readValue(ch.settle(1).single())
            assertThat(msg["type"]).isEqualTo("group.decided")
            assertThat(msg["streamId"]).isNotNull()
        }
    }

    @Test
    fun `O28 2명 모두 부정 실패 — 두 번째 실패로 즉시 실패`() {
        val g = Group().write(
            Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)),
            Member(b, "bob", st = "CHEAT_FAILED", p = 0),
        )
        val r = g.fail(a, at = hms(7, 40))

        assertThat(r.groupStatus).isEqualTo("FAILED")
        assertThat(g.field("result")).isEqualTo("FAILED_IMMEDIATE")
        assertThat(g.events().count { it["type"] == "group.decided" }).isEqualTo(1)
    }

    @Test
    fun `O6 Lua — 명단에 없는 uid 의 실패 신호는 NOT_PARTICIPANT`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0))
        val r = g.fail(999L, at = hms(7, 40))

        assertThat(listOf(r.code, r.progress, r.status, r.groupStatus, r.version)).containsExactly("NOT_PARTICIPANT", -1, "", "", 0L)
    }

    @Test
    fun `O7 Lua — 1명 그룹의 실패 신호는 SOLO_GROUP`() {
        assertThat(Group().write(Member(a, "amy")).fail(a, at = hms(7, 10)).code).isEqualTo("SOLO_GROUP")
    }

    @Test
    fun `O8 Lua — 미션 시작 전 실패 신호는 MISSION_NOT_STARTED · 명단 없음은 NO_ROSTER`() {
        val g = Group().twoMembers(Member(a, "amy", st = "NOT_STARTED", p = -1))
        assertThat(g.fail(a, at = hms(7, 10)).code).isEqualTo("MISSION_NOT_STARTED")
        assertThat(g.version()).isZero()

        val none = Group()
        none.fixture.writeRosterOnly(null)
        assertThat(none.fail(a, at = hms(6, 59)).code).isEqualTo("NO_ROSTER")
    }

    @Test
    fun `LLD 쓰기 전 검사 — 결과를 쓸 실패 신호에서 다른 그룹원 칸이 손상됐으면 아무것도 쓰지 않는다`() {
        val g = Group().write(
            Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)),
            Member(b, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
        )
        g.set("$b:pn" to "4")
        val before = g.hash()

        assertThatThrownBy { g.fail(a, at = hms(7, 40)) }.hasStackTraceContaining("ranking.fromHash: bad")
        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `LLD 쓰기 전 검사 — 결과를 쓰지 않을 실패 신호는 다른 그룹원 칸을 읽지 않는다`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        g.set("$b:pn" to "4")

        assertThat(g.fail(a, at = hms(7, 30)).code).isEqualTo("OK")
    }

    @Test
    fun `LLD 상태 손상 — 표 밖 그룹원 상태는 덮지 않고 스크립트 오류`() {
        val g = Group().twoMembers(Member(a, "amy", st = "IN_PROGRES", p = 1, cur = Q2, ls = hms(7, 10)))
        val before = g.hash()

        assertThatThrownBy { g.fail(a, at = hms(7, 30)) }.hasStackTraceContaining("bad $a:st")
        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `LLD 입력 형식 — 시각 · 진행도 칸이 어긋나면 스크립트 오류`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, ls = hms(7, 10)))
        assertThatThrownBy { g.raw("fail", listOf("$a", g.gid.toString(), date.toString(), "0", "x", "0", "0")) }
            .hasStackTraceContaining("deadlineAt must be an integer")
        g.set("$a:p" to "1.5")
        assertThatThrownBy { g.fail(a, at = hms(7, 30)) }.hasStackTraceContaining("bad $a:p")
        assertThat(g.version()).isZero()
    }
}
