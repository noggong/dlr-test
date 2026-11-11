package com.delightroom.mission.app.api.signal

import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.core.domain.group.GroupId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime

/**
 * ORACLE solve-signal O7 · O8(명단 있음) · 패스 · 실패의 200 계약(O18 · O19 · O23 의 api 쪽) — 지금 = 07:10.
 * 07:00 전(O8 명단 없음 · O9)은 [FailureApiTest]. 판정 자체(Lua)는 `infra.redis.signal.*` 가 본다.
 */
class PassApiTest : SignalApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at0710(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(7, 10))
    }

    @Test
    fun `O7 1명 그룹(명단 size 1 · 그룹 Hash 없음) — 세 엔드포인트 모두 409 SOLO_GROUP`() {
        group(Member(ME, "me_1"))

        assertThat(code(post(SOLVES, access(ME), solveBody()), 409)).isEqualTo("SOLO_GROUP")
        assertThat(code(post(PASSES, access(ME), passBody()), 409)).isEqualTo("SOLO_GROUP")
        assertThat(code(post(FAILURES, access(ME)), 409)).isEqualTo("SOLO_GROUP")
        assertThat(hash()).isEmpty()
    }

    @Test
    fun `O7 (바탕) 07시 이후 명단 없음 — 가입한 1명 그룹은 명단을 만든 뒤 409 SOLO_GROUP`() {
        val (loginId, login) = loggedIn()
        val access = cookieValue(login, AuthCookies.ACCESS)
        val uid = userIdOf(loginId)
        val groupId = GroupId(queryLong("SELECT group_id FROM users WHERE id = :id", "id" to uid.value)!!)

        assertThat(code(post(SOLVES, access, solveBody()), 409)).isEqualTo("SOLO_GROUP")
        assertThat(code(post(FAILURES, access), 409)).isEqualTo("SOLO_GROUP")

        val roster = roster(groupId)
        assertThat(roster["#size"]).isEqualTo("1")
        assertThat(roster["${uid.value}"]).isEqualTo(loginId)
    }

    @Test
    fun `O8 미션 시작 전(st NOT_STARTED) — 세 엔드포인트 모두 409 MISSION_NOT_STARTED · version +0`() {
        group(Member(ME, "me_1", st = "NOT_STARTED", p = -1), Member(OTHER, "other_2"))

        assertThat(code(post(SOLVES, access(ME), solveBody()), 409)).isEqualTo("MISSION_NOT_STARTED")
        assertThat(code(post(PASSES, access(ME), passBody()), 409)).isEqualTo("MISSION_NOT_STARTED")
        assertThat(code(post(FAILURES, access(ME)), 409)).isEqualTo("MISSION_NOT_STARTED")
        assertThat(version()).isZero()
        assertThat(events()).isEmpty()
    }

    @Test
    fun `O18 · O19 (api) 패스 200 — passedAtQuestionNo · questionNo(요청 값) · progress, 재시도는 같은 본문 · 1회 반영`() {
        group(Member(ME, "me_1", p = 1, cur = Q2), Member(OTHER, "other_2"))

        val first = body(post(PASSES, access(ME), passBody(no = 2, qid = Q2)), 200)
        val again = body(post(PASSES, access(ME), passBody(no = 2, qid = Q2)), 200)

        assertThat(first).isEqualTo(mapOf("passedAtQuestionNo" to 2, "questionNo" to 2, "progress" to 1))
        assertThat(again).isEqualTo(first)
        assertThat(hash()["$ME:pn"]).isEqualTo("2")
        assertThat(hash()).doesNotContainKey("$ME:cur")
        assertThat(version()).isEqualTo(1)
        assertThat(events()).containsExactly("pass.used")
    }

    @Test
    fun `O20 (api) 다른 번호로 두 번째 패스는 409 PASS_ALREADY_USED`() {
        group(Member(ME, "me_1", p = 1, cur = Q2), Member(OTHER, "other_2"))
        body(post(PASSES, access(ME), passBody(no = 2, qid = Q2)), 200)

        assertThat(code(post(PASSES, access(ME), passBody(no = 2, qid = Q1)), 409)).isEqualTo("PASS_ALREADY_USED")
    }

    @Test
    fun `O16 (api) 지금 문제와 다른 풀이는 409 QUESTION_MISMATCH · 번호 건너뛰기는 QUESTION_OUT_OF_ORDER`() {
        group(Member(ME, "me_1", p = 0, cur = Q1), Member(OTHER, "other_2"))

        assertThat(code(post(SOLVES, access(ME), solveBody(no = 1, qid = Q2)), 409)).isEqualTo("QUESTION_MISMATCH")
        assertThat(code(post(SOLVES, access(ME), solveBody(no = 3, qid = Q1)), 409)).isEqualTo("QUESTION_OUT_OF_ORDER")
        assertThat(version()).isZero()
    }

    @Test
    fun `O23 (api) 부정 실패 200 — 진행도 그대로 · CHEAT_FAILED, 재시도는 같은 본문 · 1회 반영`() {
        group(Member(ME, "me_1", p = 1), Member(OTHER, "other_2"))

        val first = body(post(FAILURES, access(ME)), 200)
        val again = body(post(FAILURES, access(ME), mapOf("ignored" to true)), 200)

        assertThat(first).isEqualTo(mapOf("progress" to 1, "missionStatus" to "CHEAT_FAILED", "groupStatus" to "IN_PROGRESS"))
        assertThat(again).isEqualTo(first)
        assertThat(version()).isEqualTo(1)
        assertThat(events()).containsExactly("member.cheat_failed")
    }

    @Test
    fun `O25 (api) 미션 성공 뒤 실패 신호는 무시 — 200 SUCCEEDED · version +0`() {
        group(Member(ME, "me_1", st = "SUCCEEDED", p = 3), Member(OTHER, "other_2"))

        assertThat(body(post(FAILURES, access(ME)), 200))
            .isEqualTo(mapOf("progress" to 3, "missionStatus" to "SUCCEEDED", "groupStatus" to "IN_PROGRESS"))
        assertThat(version()).isZero()
    }
}
