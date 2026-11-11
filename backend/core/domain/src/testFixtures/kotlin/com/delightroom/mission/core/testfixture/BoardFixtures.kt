package com.delightroom.mission.core.testfixture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * realtime-board SPEC §3 응답 예시의 4명 그룹(`junho` · `minji` · `dohyun` · `hana`) 그룹 Hash 필드(ORACLE realtime-board 테스트 규약).
 * `hana` 는 진행도 1 에서 부정 실패, `dohyun` 은 미접속. core · infra · app 테스트가 같은 값을 `HSET` 하거나 파서에 넣는다.
 */
object BoardFixtures {
    val DATE: LocalDate = LocalDate.of(2026, 11, 12)
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    const val VERSION = 17L

    const val JUNHO = 101L
    const val MINJI = 102L
    const val DOHYUN = 103L
    const val HANA = 104L

    /** 게임 날짜 KST 시각 → 에포크 µs. */
    fun micros(time: LocalTime): Long {
        val instant = LocalDateTime.of(DATE, time).atZone(ZONE).toInstant()
        return Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1_000L)
    }

    val JUNHO_LS = micros(LocalTime.parse("07:03:41.120384"))
    val MINJI_LS = micros(LocalTime.parse("07:04:02.553001"))
    val HANA_LS = micros(LocalTime.parse("07:02:10.000250"))

    /** 결과 확정 전(SPEC §3 예시) 그룹 Hash. */
    fun inProgressHash(): Map<String, String> =
        linkedMapOf(
            "version" to "$VERSION",
            "$JUNHO:lid" to "junho", "$JUNHO:st" to "IN_PROGRESS", "$JUNHO:p" to "2", "$JUNHO:late" to "0",
            "$JUNHO:ls" to "$JUNHO_LS",
            "$MINJI:lid" to "minji", "$MINJI:st" to "IN_PROGRESS", "$MINJI:p" to "2", "$MINJI:late" to "0",
            "$MINJI:ls" to "$MINJI_LS", "$MINJI:pn" to "1",
            "$DOHYUN:lid" to "dohyun", "$DOHYUN:st" to "NOT_STARTED", "$DOHYUN:p" to "-1",
            "$HANA:lid" to "hana", "$HANA:st" to "CHEAT_FAILED", "$HANA:p" to "1", "$HANA:late" to "0",
            "$HANA:ls" to "$HANA_LS",
        )

    /**
     * 08:00 마감 확정 뒤 그룹 Hash — 미완료 셋은 `FAILED`, `hana` 는 `CHEAT_FAILED`. `ranking` 은 [ranks](uid → 순위)로 쓴다
     * (줄의 key 순서는 cjson 처럼 일정하지 않게 섞는다).
     */
    fun decidedHash(
        ranks: Map<Long, Int> = mapOf(JUNHO to 1, MINJI to 2, DOHYUN to 3, HANA to 4),
        result: String = "FAILED_DEADLINE",
    ): Map<String, String> {
        val hash = LinkedHashMap(inProgressHash())
        listOf(JUNHO, MINJI, DOHYUN).forEach { hash["$it:st"] = "FAILED" }
        hash["version"] = "${VERSION + 1}"
        hash["result"] = result
        hash["decidedAt"] = "${micros(LocalTime.of(8, 0, 0, 1_000))}"
        hash["rankingFinal"] = "0"
        hash["ranking"] = ranks.entries.sortedBy { it.value }.joinToString(",", "[", "]") { (uid, r) ->
            """{"r":$r,"id":"$uid","u":"${hash["$uid:lid"]}","s":"${hash["$uid:st"]}","p":${hash["$uid:p"]},"l":null,"n":null}"""
        }
        return hash
    }

    /**
     * [inProgressHash] 를 `minji` 가 볼 때의 `entries` JSON 원문(SPEC §6.1 예시 그대로 · 한 줄). 현황판 조회 응답과 SSE `board.snapshot` 이
     * 이 문자열과 **바이트까지** 같으면 둘이 서로 같다(ORACLE O23 · RB12).
     */
    const val MINJI_ENTRIES_JSON =
        """[{"userId":"junho","rank":1,"progress":2,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:03:41.120384+09:00","passedAtQuestionNo":null},""" +
            """{"userId":"minji","rank":2,"progress":2,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:04:02.553001+09:00","passedAtQuestionNo":1},""" +
            """{"userId":"dohyun","rank":3,"progress":0,"status":"NOT_STARTED","started":false,"lastSolvedAt":null,"passedAtQuestionNo":null},""" +
            """{"userId":"hana","rank":4,"progress":1,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:02:10.000250+09:00","passedAtQuestionNo":null}]"""

    /** 응답 JSON 원문에서 `"entries":[…]` 의 배열 부분을 다시 직렬화하지 않고 그대로 잘라 낸다(줄 객체 안에 배열이 없다). */
    fun rawEntries(json: String): String {
        val start = json.indexOf("\"entries\":[")
        require(start >= 0) { "entries 없음: $json" }
        val from = start + "\"entries\":".length
        return json.substring(from, json.indexOf(']', from) + 1)
    }

    /** [fields] 를 `HGETALL` 응답 같은 평탄 목록으로. */
    fun flat(fields: Map<String, String>): List<String> = fields.flatMap { (k, v) -> listOf(k, v) }
}
