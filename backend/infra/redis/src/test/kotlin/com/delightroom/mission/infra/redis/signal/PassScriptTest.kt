package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.infra.redis.signal.GroupHashFixture.Member
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** ORACLE solve-signal O15 · O16 (패스) · O18 ~ O22 · O6 ~ O9 (Lua 수준) — Lua `pass` 판정 순서 · 하루 1번 · 멱등 · 유예 없음. */
class PassScriptTest : SignalScriptSupport() {
    private val json = jacksonObjectMapper()

    private val a = 301L
    private val b = 302L

    private fun Group.twoMembers(me: Member, group: Map<String, String> = emptyMap()) =
        write(me, Member(b, "bob", st = "IN_PROGRESS", p = 0), group = group)

    @Test
    fun `O18 패스 정상 반영 — 패스 번호 · 넘긴 문제 · 지금 문제 · version · Stream · 채널`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        g.listen().use { ch ->
            val r = g.pass(a, 2, Q2, at = hms(7, 20))

            assertThat(r.code).isEqualTo("OK")
            assertThat(r.passedAtQuestionNo).isEqualTo(2)
            assertThat(listOf(r.reply.progress, r.reply.status, r.reply.groupStatus)).containsExactly(1, "IN_PROGRESS", "IN_PROGRESS")
            assertThat(r.reply.version).isEqualTo(1)
            val h = g.hash()
            assertThat(h["$a:pn"]).isEqualTo("2")
            assertThat(h["$a:pq"]).isEqualTo(Q2)
            assertThat(h["$a:p"]).isEqualTo("1")
            assertThat(h).doesNotContainKey("$a:cur")
            assertThat(h["version"]).isEqualTo("1")

            val ev = g.events().single()
            assertThat(ev).containsAllEntriesOf(
                mapOf(
                    "type" to "pass.used", "date" to "2026-11-12", "gid" to g.gid.toString(), "uid" to "$a", "lid" to "amy",
                    "version" to "1", "at" to r.reply.t.toString(), "questionNo" to "2", "questionId" to Q2,
                ),
            )

            val msg: Map<String, Any?> = json.readValue(ch.settle(1).single())
            assertThat(msg["type"]).isEqualTo("pass.used")
            assertThat(msg["version"]).isEqualTo(1)
            assertThat(msg["uid"]).isEqualTo("$a")
            assertThat(msg["streamId"]).isInstanceOf(String::class.java)
            @Suppress("UNCHECKED_CAST")
            assertThat(msg["hash"] as Map<String, String>).isEqualTo(h)
        }
    }

    @Test
    fun `O19 패스 재시도는 07_21 · 08_00_05 모두 같은 본문 · 무반영`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        val first = g.pass(a, 2, Q2, at = hms(7, 20))
        val events = g.events().size
        g.listen().use { ch ->
            // groupStatus 는 SS9 · SS14 의 지금 값 — 08:00 이 지나면 PENDING.
            listOf(hms(7, 21) to "IN_PROGRESS", hms(8, 0, 5) to "PENDING").forEach { (at, groupStatus) ->
                val again = g.pass(a, 2, Q2, at = at)

                assertThat(again.code).isEqualTo("REPLAY")
                assertThat(again.reply.groupStatus).isEqualTo(groupStatus)
                assertThat(again.passedAtQuestionNo).isEqualTo(first.passedAtQuestionNo)
                assertThat(listOf(again.reply.progress, again.reply.status, again.reply.version))
                    .isEqualTo(listOf(first.reply.progress, first.reply.status, first.reply.version))
            }
            assertThat(g.version()).isEqualTo(1)
            assertThat(g.events()).hasSize(events)
            assertThat(ch.settle(0)).isEmpty()
        }
    }

    @Test
    fun `O18 시간 창 경계 — 07_00_00_000 · 07_59_59_900 도착은 반영`() {
        // 오프셋 방식이라 실제 t 는 목표보다 왕복 지연만큼 늦다 — 07:00 은 그대로, 08:00 직전은 100 ms 여유를 둔다.
        listOf(hms(7, 0), hms(7, 59, 59, 900)).forEach { at ->
            val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(6, 59)))
            val r = g.pass(a, 2, Q2, at = at)

            assertThat(r.code).`as`("$at").isEqualTo("OK")
            assertThat(r.reply.t).isGreaterThanOrEqualTo(at).isLessThan(deadlineAt)
        }
    }

    @Test
    fun `O20 두 번째 패스는 PASS_ALREADY_USED · 패스 번호 그대로`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        g.pass(a, 2, Q2, at = hms(7, 20))
        val q2b = "b3".padEnd(64, '0') // 패스 뒤 같은 번호의 새 문제 → 풀어 진행도 2 · 다음 문제 Q3
        g.set("$a:cur" to q2b)
        assertThat(g.solve(a, 2, q2b, at = hms(7, 25)).code).isEqualTo("OK")
        g.set("$a:cur" to Q3)

        val r = g.pass(a, 3, Q3, at = hms(7, 30))

        assertThat(r.code).isEqualTo("PASS_ALREADY_USED")
        assertThat(r.passedAtQuestionNo).isEqualTo(2)
        val h = g.hash()
        assertThat(h["$a:pn"]).isEqualTo("2")
        assertThat(h["$a:pq"]).isEqualTo(Q2)
        assertThat(h["$a:cur"]).isEqualTo(Q3)
        assertThat(g.events().count { it["type"] == "pass.used" }).isEqualTo(1)
    }

    @Test
    fun `O20 같은 번호라도 다른 문제로 다시 패스하면 PASS_ALREADY_USED`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        g.pass(a, 2, Q2, at = hms(7, 20))
        g.set("$a:cur" to QX)

        assertThat(g.pass(a, 2, QX, at = hms(7, 21)).code).isEqualTo("PASS_ALREADY_USED")
        assertThat(g.version()).isEqualTo(1)
    }

    @Test
    fun `O21 패스는 유예 없음 — 08_00_00_500 은 SUBMISSION_CLOSED`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)))
        val r = g.pass(a, 2, Q2, at = hms(8, 0, 0, 500))

        assertThat(r.code).isEqualTo("SUBMISSION_CLOSED")
        assertThat(r.reply.groupStatus).isEqualTo("PENDING")
        assertThat(g.version()).isZero()
        assertThat(g.events()).isEmpty()
        assertThat(g.hash()).doesNotContainKey("$a:pn")
    }

    @Test
    fun `O21 결과가 난 그룹은 08_00 전이어도 패스 SUBMISSION_CLOSED`() {
        val g = Group().twoMembers(Member(a, "amy", p = 1, cur = Q2, ls = hms(7, 10)), group = mapOf("result" to "FAILED_IMMEDIATE"))
        val r = g.pass(a, 2, Q2, at = hms(7, 50))

        assertThat(listOf(r.code, r.reply.groupStatus)).containsExactly("SUBMISSION_CLOSED", "FAILED")
        assertThat(g.version()).isZero()
    }

    @Test
    fun `O22 완료 뒤 패스 — SUCCEEDED · CHEAT_FAILED 모두 MISSION_ALREADY_FINISHED`() {
        val ok = Group().twoMembers(Member(a, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)))
        assertThat(ok.pass(a, 3, Q3, at = hms(7, 40)).code).isEqualTo("MISSION_ALREADY_FINISHED")

        val cheat = Group().twoMembers(Member(a, "amy", st = "CHEAT_FAILED", p = 1, cur = Q2, ls = hms(7, 10)))
        assertThat(cheat.pass(a, 2, Q2, at = hms(7, 40)).code).isEqualTo("MISSION_ALREADY_FINISHED")
        assertThat(ok.version() + cheat.version()).isZero()
    }

    @Test
    fun `O15 패스 번호를 건너뛰면 QUESTION_OUT_OF_ORDER`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        assertThat(g.pass(a, 2, Q2, at = hms(7, 10)).code).isEqualTo("QUESTION_OUT_OF_ORDER")
        assertThat(g.version()).isZero()
    }

    @Test
    fun `O16 패스 지금 문제 불일치 · 없음은 QUESTION_MISMATCH`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        assertThat(g.pass(a, 1, QX, at = hms(7, 10)).code).isEqualTo("QUESTION_MISMATCH")

        val none = Group().twoMembers(Member(a, "amy", p = 0))
        assertThat(none.pass(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("QUESTION_MISMATCH")
        assertThat(g.version() + none.version()).isZero()
    }

    @Test
    fun `O6 Lua — 명단에 없는 uid 의 패스는 NOT_PARTICIPANT`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        val r = g.pass(999L, 1, Q1, at = hms(7, 40))

        assertThat(listOf(r.code, r.reply.progress, r.reply.status, r.reply.groupStatus, r.reply.version, r.passedAtQuestionNo))
            .containsExactly("NOT_PARTICIPANT", -1, "", "", 0L, 0)
    }

    @Test
    fun `O7 Lua — 1명 그룹의 패스는 SOLO_GROUP`() {
        assertThat(Group().write(Member(a, "amy")).pass(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("SOLO_GROUP")
    }

    @Test
    fun `O8 Lua — 미션 시작 전 패스는 MISSION_NOT_STARTED · 명단 없음은 NO_ROSTER`() {
        val g = Group().twoMembers(Member(a, "amy", st = "NOT_STARTED", p = -1))
        assertThat(g.pass(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("MISSION_NOT_STARTED")
        assertThat(g.version()).isZero()

        val none = Group()
        none.fixture.writeRosterOnly(null)
        assertThat(none.pass(a, 1, Q1, at = hms(7, 10)).code).isEqualTo("NO_ROSTER")
    }

    @Test
    fun `O9 Lua — 07_00 전 패스는 GAME_NOT_OPEN`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        assertThat(g.pass(a, 1, Q1, at = hms(6, 59, 59)).code).isEqualTo("GAME_NOT_OPEN")
        assertThat(g.version()).isZero()
    }

    @Test
    fun `LLD 상태 손상 — 표 밖 그룹원 상태는 덮지 않고 스크립트 오류`() {
        val g = Group().twoMembers(Member(a, "amy", st = "IN_PROGRES", p = 1, cur = Q2, ls = hms(7, 10)))
        val before = g.hash()

        assertThatThrownBy { g.pass(a, 2, Q2, at = hms(7, 20)) }.hasStackTraceContaining("bad $a:st")
        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `LLD 입력 형식 — 패스 번호 · 시각 · 패스 칸이 어긋나면 스크립트 오류`() {
        val g = Group().twoMembers(Member(a, "amy", p = 0, cur = Q1))
        fun args(no: String, open: String = "0") = listOf("$a", g.gid.toString(), date.toString(), open, "0", "0", "0", no, Q1)
        listOf("0", "4", "1.0", "", "x").forEach { no ->
            assertThatThrownBy { g.raw("pass", args(no)) }.hasStackTraceContaining("questionNo must be 1~3")
        }
        assertThatThrownBy { g.raw("pass", args("1", open = "1e15")) }.hasStackTraceContaining("openAt must be an integer")

        g.set("$a:pn" to "4")
        assertThatThrownBy { g.pass(a, 1, Q1, at = hms(7, 10)) }.hasStackTraceContaining("bad $a:pn")
        assertThat(g.version()).isZero()
        assertThat(g.events()).isEmpty()
    }
}
