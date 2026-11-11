package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.LocalTime

/**
 * ORACLE realtime-board O1 · O2 · O4 ~ O6 · O23(조회 쪽) — `GET /api/v1/games/today/board` 를 07:30(창 안)에 부른다.
 * 창 밖(O3 API 1건)은 [BoardApiClosedTest].
 */
class BoardApiTest : BoardApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at0730(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(7, 30))

        /** SPEC §3 응답 예시 원문(`minji` 가 보는 화면). */
        private val SPEC_EXAMPLE = """
            {
              "date": "2026-11-12",
              "groupStatus": "IN_PROGRESS",
              "version": 17,
              "entries": [
                { "userId": "junho",  "rank": 1, "progress": 2, "status": "IN_PROGRESS", "started": true,  "lastSolvedAt": "2026-11-12T07:03:41.120384+09:00", "passedAtQuestionNo": null },
                { "userId": "minji",  "rank": 2, "progress": 2, "status": "IN_PROGRESS", "started": true,  "lastSolvedAt": "2026-11-12T07:04:02.553001+09:00", "passedAtQuestionNo": 1 },
                { "userId": "dohyun", "rank": 3, "progress": 0, "status": "NOT_STARTED", "started": false, "lastSolvedAt": null, "passedAtQuestionNo": null },
                { "userId": "hana",   "rank": 4, "progress": 1, "status": "IN_PROGRESS", "started": true,  "lastSolvedAt": "2026-11-12T07:02:10.000250+09:00", "passedAtQuestionNo": null }
              ]
            }
        """.trimIndent()
    }

    private val json = jacksonObjectMapper()

    private fun body(spec: org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec, status: Int): Map<String, Any?> {
        val raw = spec.expectStatus().isEqualTo(status).expectBody(String::class.java).returnResult().responseBody!!
        return json.readValue(raw)
    }

    @Test
    fun `O1 창 안에서 minji 로 조회하면 200 이고 SPEC 3 응답 예시와 같다`() {
        fourMembers()
        val actual = body(getBoard(access(MINJI)), 200)
        assertThat(actual.keys).containsExactly("date", "groupStatus", "version", "entries")
        @Suppress("UNCHECKED_CAST")
        val entries = actual["entries"] as List<Map<String, Any?>>
        assertThat(entries).hasSize(4).allSatisfy { assertThat(it).hasSize(7) }
        assertThat(actual).isEqualTo(json.readValue<Map<String, Any?>>(SPEC_EXAMPLE))
    }

    @Test
    fun `O1 보는 사람별 — hana 가 보면 자기 줄은 CHEAT_FAILED, 순위는 같다`() {
        fourMembers()
        val actual = body(getBoard(access(BoardFixtures.HANA)), 200)
        @Suppress("UNCHECKED_CAST")
        val entries = actual["entries"] as List<Map<String, Any?>>
        assertThat(entries.map { it["userId"] }).containsExactly("junho", "minji", "dohyun", "hana")
        assertThat(entries.last()["status"]).isEqualTo("CHEAT_FAILED")
        assertThat(entries.last()["rank"]).isEqualTo(4)
    }

    @Test
    fun `O23 minji 의 조회 entries 원문은 SSE snapshot 과 같은 기준 문자열과 바이트까지 같다`() {
        fourMembers()
        val raw = getBoard(access(MINJI)).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        // 같은 문자열을 app:realtime EventStreamTest 가 board.snapshot 에 대해 확인한다 — 둘이 같으면 조회 = 스트림(RB12)
        assertThat(BoardFixtures.rawEntries(raw)).isEqualTo(BoardFixtures.MINJI_ENTRIES_JSON)
    }

    @Test
    fun `O2 쿠키 없음 · 만료 토큰은 401 UNAUTHORIZED`() {
        fourMembers()
        assertThat(body(getBoard(null), 401)["code"]).isEqualTo("UNAUTHORIZED")
        val expired = access(MINJI, issuedAt = gameProperties.now(clock).minus(Duration.ofHours(2)))
        assertThat(body(getBoard(expired), 401)["code"]).isEqualTo("UNAUTHORIZED")
    }

    @Test
    fun `O4 명단에 없는 유저는 403 NOT_PARTICIPANT`() {
        fourMembers()
        val actual = body(getBoard(access(999L)), 403)
        assertThat(actual).isEqualTo(mapOf("code" to "NOT_PARTICIPANT", "message" to "오늘 게임의 그룹원이 아닙니다."))
    }

    @Test
    fun `O5 그날 그룹원 1명이면 409 SOLO_GROUP`() {
        roster(mapOf(MINJI to "minji"))
        hset(groupKey, mapOf("version" to "0", "$MINJI:lid" to "minji", "$MINJI:st" to "NOT_STARTED", "$MINJI:p" to "-1"))
        assertThat(body(getBoard(access(MINJI)), 409)["code"]).isEqualTo("SOLO_GROUP")
    }

    @Test
    fun `O6 명단 없음 · 그룹 Hash 없음은 409 GAME_NOT_OPEN`() {
        assertThat(body(getBoard(access(MINJI)), 409)["code"]).isEqualTo("GAME_NOT_OPEN")
        roster(MEMBERS)
        assertThat(body(getBoard(access(MINJI)), 409)["code"]).isEqualTo("GAME_NOT_OPEN")
    }

    @Test
    fun `O7 그룹원 아님 + 1명 그룹이면 먼저 걸린 NOT_PARTICIPANT`() {
        roster(mapOf(MINJI to "minji"))
        assertThat(body(getBoard(access(999L)), 403)["code"]).isEqualTo("NOT_PARTICIPANT")
    }

    @Test
    fun `그룹 Hash 가 틀리면 500 — 따로 처리하지 않는다(LLD 4_1 4)`() {
        roster(MEMBERS)
        hset(groupKey, BoardFixtures.inProgressHash() - "version")
        getBoard(access(MINJI)).expectStatus().isEqualTo(500)
    }
}
