package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.infra.redis.signal.GroupHashFixture.Member
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** ORACLE solve-signal O1 ~ O3 · O10 ~ O17 · O27 · O28① · O29 (Lua 수준) — Lua `solve` 판정 순서 · 반영 · 기록 · 전달. */
class SolveScriptTest : SignalScriptSupport() {
    private val json = jacksonObjectMapper()

    private val a = 101L
    private val b = 102L
    private val c = 103L

    private fun Group.twoMembers(me: Member, group: Map<String, String> = emptyMap()) =
        write(me, Member(b, "bob", st = "IN_PROGRESS", p = 0), group = group)

    @Test
    fun `O1 풀이 정상 반영 — 진행도 · 도착 시각 · 지금 문제 · version · Stream · 채널`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        g.listen().use { ch ->
            val r = g.solve(a, 1, Q1, at = hms(7, 10), successTime = "2026-11-12T07:09:59.123+09:00")

            assertThat(r.code).isEqualTo("OK")
            assertThat(listOf(r.progress, r.status, r.groupStatus)).containsExactly(1, "IN_PROGRESS", "IN_PROGRESS")
            assertThat(r.version).isEqualTo(1)
            val h = g.hash()
            assertThat(h["$a:p"]).isEqualTo("1")
            assertThat(h["$a:ls"]).isEqualTo(r.t.toString())
            assertThat(h).doesNotContainKey("$a:cur")
            assertThat(h["version"]).isEqualTo("1")

            val ev = g.events().single()
            assertThat(ev).containsAllEntriesOf(
                mapOf(
                    "type" to "solve.applied", "date" to "2026-11-12", "gid" to g.gid.toString(), "uid" to "$a", "lid" to "amy",
                    "version" to "1", "at" to r.t.toString(), "questionNo" to "1", "questionId" to Q1,
                    "successTime" to "2026-11-12T07:09:59.123+09:00", "clickedAt" to hms(7, 10).toString(),
                    "arrivedAt" to r.t.toString(), "progress" to "1", "status" to "IN_PROGRESS", "grace" to "0",
                ),
            )

            val msg: Map<String, Any?> = json.readValue(ch.settle(1).single())
            assertThat(msg["type"]).isEqualTo("solve.applied")
            assertThat(msg["version"]).isEqualTo(1)
            assertThat(msg["uid"]).isEqualTo("$a")
            assertThat(msg["gid"]).isEqualTo(g.gid.toString())
            assertThat(msg["date"]).isEqualTo("2026-11-12")
            assertThat(msg["at"]).isEqualTo(r.t.toString())
            assertThat(msg["streamId"]).isInstanceOf(String::class.java)
            @Suppress("UNCHECKED_CAST")
            assertThat(msg["hash"] as Map<String, String>).isEqualTo(h)
        }
    }

    @Test
    fun `O2 풀이 재시도는 같은 본문 · 무반영 · 채널 0건`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        val first = g.solve(a, 1, Q1, at = hms(7, 10))
        val events = g.events().size
        g.listen().use { ch ->
            val again = g.solve(a, 1, Q1, at = hms(7, 10, 1))

            assertThat(again.code).isEqualTo("REPLAY")
            assertThat(again.body).isEqualTo(first.body)
            assertThat(g.version()).isEqualTo(1)
            assertThat(g.events()).hasSize(events)
            assertThat(ch.settle(0)).isEmpty()
        }
    }

    @Test
    fun `O3 3번 반영이면 미션 성공 · 그룹은 아직 진행 중`() {
        val g = Group().twoMembers(Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 5)))
        val r = g.solve(a, 3, Q3, at = hms(7, 20))

        assertThat(listOf(r.code, r.progress, r.status, r.groupStatus)).containsExactly("OK", 3, "SUCCEEDED", "IN_PROGRESS")
        assertThat(g.field("$a:st")).isEqualTo("SUCCEEDED")
        assertThat(g.field("result")).isNull()
        assertThat(g.events().map { it["type"] }).containsExactly("solve.applied")
    }

    @Test
    fun `O10 부정 실패 뒤 새 번호는 거부 · 옛 번호 재시도는 200`() {
        val g = Group().twoMembers(Member(a, "amy", st = "CHEAT_FAILED", p = 1, cur = Q2, ls = hms(7, 5)))
        val newer = g.solve(a, 2, Q2, at = hms(7, 30))
        val older = g.solve(a, 1, Q1, at = hms(7, 30))

        assertThat(newer.code).isEqualTo("MISSION_ALREADY_FINISHED")
        assertThat(listOf(older.code, older.progress, older.status)).containsExactly("REPLAY", 1, "CHEAT_FAILED")
        assertThat(g.version()).isZero()
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `O11 유예 구간이어도 클릭이 08_00 이후면 SUBMISSION_CLOSED`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        val r = g.solve(a, 1, Q1, at = hms(8, 0, 1), clickedAt = hms(8, 0, 0))

        assertThat(r.code).isEqualTo("SUBMISSION_CLOSED")
        assertThat(r.groupStatus).isEqualTo("PENDING")
        assertThat(g.version()).isZero()
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `O12 도착 08_00_00_000 · 클릭 07_59_59_999 는 유예로 반영`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        val r = g.solve(a, 1, Q1, at = hms(8, 0, 0), clickedAt = hms(7, 59, 59, 999))

        assertThat(r.code).isEqualTo("OK")
        assertThat(r.t - hms(8, 0, 0)).isBetween(0L, 50_000L)
        assertThat(g.events().single()["grace"]).isEqualTo("1")
    }

    @Test
    fun `O13 08_00 확정 그룹의 유예 풀이 — 진행도 · 랭킹만 바뀐다`() {
        val stale = """[{"id":"$b","u":"bob","r":1,"p":1,"s":"FAILED","l":"${hms(7, 1)}","n":null},""" +
            """{"id":"$a","u":"amy","r":2,"p":1,"s":"FAILED","l":"${hms(7, 5)}","n":null}]"""
        val g = Group().write(
            Member(a, "amy", st = "FAILED", p = 1, cur = Q2, ls = hms(7, 5)),
            Member(b, "bob", st = "FAILED", p = 1, ls = hms(7, 1)),
            group = mapOf("result" to "FAILED_DEADLINE", "rankingFinal" to "0", "decidedAt" to hms(8, 0).toString(), "ranking" to stale),
        )
        g.listen().use { ch ->
            val r = g.solve(a, 2, Q2, at = hms(8, 0, 1), clickedAt = hms(7, 59, 59))

            assertThat(listOf(r.code, r.progress, r.status, r.groupStatus)).containsExactly("OK", 2, "FAILED", "FAILED")
            val h = g.hash()
            assertThat(h["result"]).isEqualTo("FAILED_DEADLINE")
            assertThat(h["rankingFinal"]).isEqualTo("0")
            val ranking: List<Map<String, Any?>> = json.readValue(h["ranking"]!!)
            assertThat(ranking.map { it["u"] to it["r"] }).containsExactly("amy" to 1, "bob" to 2)
            assertThat(ranking.first()["p"]).isEqualTo(2)
            assertThat(ranking.first()["l"]).isEqualTo(r.t.toString())
            assertThat(g.events().map { it["type"] }).containsExactly("solve.applied")
            assertThat(g.events().single()["grace"]).isEqualTo("1")
            val msg: Map<String, Any?> = json.readValue(ch.settle(1).single())
            assertThat(msg["type"]).isEqualTo("solve.applied")
        }
    }

    @Test
    fun `O14 마감 뒤 재시도는 같은 진행도 · 상태 · version +0`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 5)))
        val first = g.solve(a, 2, Q2, at = hms(7, 59))
        val again = g.solve(a, 2, Q2, at = hms(8, 0, 5), clickedAt = hms(7, 59))

        assertThat(first.code).isEqualTo("OK")
        assertThat(again.code).isEqualTo("REPLAY")
        assertThat(listOf(again.progress, again.status, again.version)).containsExactly(first.progress, first.status, first.version)
        assertThat(g.version()).isEqualTo(first.version)
    }

    @Test
    fun `O15 번호를 건너뛰면 QUESTION_OUT_OF_ORDER`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        val r = g.solve(a, 2, Q2, at = hms(7, 10))

        assertThat(r.code).isEqualTo("QUESTION_OUT_OF_ORDER")
        assertThat(g.version()).isZero()
    }

    @Test
    fun `O16 지금 문제 불일치 · 없음은 QUESTION_MISMATCH`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        assertThat(g.solve(a, 1, QX, at = hms(7, 10)).code).isEqualTo("QUESTION_MISMATCH")

        val none = Group().twoMembers(Member(a, "amy", p = 0, cur = null))
        assertThat(none.solve(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("QUESTION_MISMATCH")

        assertThat(g.version() + none.version()).isZero()
        assertThat(g.events() + none.events()).isEmpty()
    }

    @Test
    fun `O17 랭킹 시각은 서버 도착 시각 — successTime 은 기록만`() {
        listOf("2026-11-12T06:00:00+09:00", "2099-01-01T00:00:00+09:00").forEach { st ->
            val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
            val r = g.solve(a, 1, Q1, at = hms(7, 10), successTime = st)

            assertThat(r.code).isEqualTo("OK")
            assertThat(g.field("$a:ls")).isEqualTo(r.t.toString())
            assertThat(g.events().single()["successTime"]).isEqualTo(st)
            assertThat(g.events().single()["arrivedAt"]).isEqualTo(r.t.toString())
        }
    }

    @Test
    fun `O27 마지막 한 명이 끝내면 그룹 성공을 한 번 기록 · 전달`() {
        val g = Group().write(
            Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)),
            Member(b, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
            Member(c, "cy", st = "SUCCEEDED", p = 3, ls = hms(7, 25), pn = 2),
        )
        g.listen().use { ch ->
            val r = g.solve(a, 3, Q3, at = hms(7, 40))

            assertThat(listOf(r.code, r.status, r.groupStatus)).containsExactly("OK", "SUCCEEDED", "SUCCEEDED")
            val h = g.hash()
            assertThat(h["result"]).isEqualTo("SUCCEEDED")
            assertThat(h["decidedAt"]).isEqualTo(r.t.toString())
            assertThat(h["rankingFinal"]).isEqualTo("1")
            assertThat(h["version"]).isEqualTo("1")

            val events = g.events()
            assertThat(events.map { it["type"] }).containsExactly("solve.applied", "group.decided")
            val decided = events[1]
            assertThat(decided).containsAllEntriesOf(
                mapOf(
                    "uid" to "$a", "lid" to "amy", "version" to "1", "at" to r.t.toString(), "result" to "SUCCEEDED",
                    "decidedAt" to r.t.toString(), "memberCount" to "3", "rankingFinal" to "1",
                ),
            )
            val ranking: List<Map<String, Any?>> = json.readValue(decided["ranking"]!!)
            assertThat(ranking.map { it["u"] to it["r"] }).containsExactly("bob" to 1, "cy" to 2, "amy" to 3)
            assertThat(json.readValue<List<Map<String, Any?>>>(h["ranking"]!!)).isEqualTo(ranking)

            val msgs = ch.settle(1)
            assertThat(msgs).hasSize(1)
            val msg: Map<String, Any?> = json.readValue(msgs.single())
            assertThat(msg["type"]).isEqualTo("group.decided")
            assertThat(msg["streamId"]).isNotNull()
        }
    }

    @Test
    fun `O28 부정 실패가 섞인 전원 완료 — 마지막 풀이로 즉시 실패`() {
        val g = Group().write(
            Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)),
            Member(b, "bob", st = "CHEAT_FAILED", p = 1, ls = hms(7, 10)),
        )
        val r = g.solve(a, 3, Q3, at = hms(7, 40))

        assertThat(listOf(r.code, r.status, r.groupStatus)).containsExactly("OK", "SUCCEEDED", "FAILED")
        assertThat(g.field("result")).isEqualTo("FAILED_IMMEDIATE")
        assertThat(g.events().count { it["type"] == "group.decided" }).isEqualTo(1)
    }

    @Test
    fun `O29 08_00 판정이 미뤄진 그룹의 유예 풀이는 PENDING`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)), group = mapOf("pending" to "1"))
        val r = g.solve(a, 2, Q2, at = hms(8, 0, 1), clickedAt = hms(7, 59, 58))

        assertThat(listOf(r.code, r.progress, r.groupStatus)).containsExactly("OK", 2, "PENDING")
        assertThat(g.field("pending")).isEqualTo("1")
    }

    @Test
    fun `O29 미룬 그룹에서 마지막 유예 풀이로 결과가 나면 pending 을 지우고 랭킹은 잠정`() {
        val g = Group().write(
            Member(a, "amy", p = 2, cur = Q3, ls = hms(7, 30)),
            Member(b, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
            group = mapOf("pending" to "1"),
        )
        val r = g.solve(a, 3, Q3, at = hms(8, 0, 1), clickedAt = hms(7, 59, 59))

        assertThat(r.groupStatus).isEqualTo("SUCCEEDED")
        val h = g.hash()
        assertThat(h).doesNotContainKey("pending")
        assertThat(h["rankingFinal"]).isEqualTo("0")
        assertThat(g.events().map { it["type"] }).containsExactly("solve.applied", "group.decided")
    }

    @Test
    fun `O6 Lua — 명단에 없는 uid 는 NOT_PARTICIPANT`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        val r = g.solve(999L, 1, Q1, at = hms(7, 40))

        assertThat(listOf(r.code, r.progress, r.status, r.groupStatus, r.version)).containsExactly("NOT_PARTICIPANT", -1, "", "", 0L)
    }

    @Test
    fun `O7 Lua — 1명 그룹은 SOLO_GROUP`() {
        val g = Group().write(Member(a, "amy"))
        assertThat(g.solve(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("SOLO_GROUP")
    }

    @Test
    fun `O8 Lua — 미션 시작 전은 MISSION_NOT_STARTED · 명단 없음은 NO_ROSTER`() {
        val g = Group().twoMembers(Member(a, "amy", st = "NOT_STARTED", p = -1))
        assertThat(g.solve(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("MISSION_NOT_STARTED")
        assertThat(g.version()).isZero()

        val none = Group()
        none.fixture.writeRosterOnly(null)
        assertThat(none.solve(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("NO_ROSTER")
    }

    @Test
    fun `O9 Lua — 07_00 전은 GAME_NOT_OPEN`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        assertThat(g.solve(a, 1, Q1, at = hms(6, 59, 59)).code).isEqualTo("GAME_NOT_OPEN")
        assertThat(g.version()).isZero()
    }

    @Test
    fun `LLD 입력 형식 — 번호 · 클릭 시각이 어긋나면 스크립트 오류`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        assertThatThrownBy { g.solve(a, 4, Q1, at = hms(7, 10)) }.hasStackTraceContaining("questionNo must be 1~3")
        assertThatThrownBy { g.solve(a, 0, Q1, at = hms(7, 10)) }.hasStackTraceContaining("questionNo must be 1~3")
        assertThat(g.version()).isZero()
    }
}
