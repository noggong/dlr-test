package com.delightroom.mission.core.testfixture

import com.delightroom.mission.core.domain.ranking.RankingKey
import com.delightroom.mission.core.domain.user.LoginId
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 랭킹 경계 사례 표(ORACLE deadline-result §1.1) — `ranking-cases.json`. Kotlin `RankingTest` 와
 * Lua `LuaRankingTest` 가 같은 파일을 읽는다(LLD deadline-result §4.2).
 */
object RankingCases {
    data class File(
        val source: String,
        val gameDate: LocalDate,
        val zone: String,
        val lateAfter: LocalTime,
        val groups: List<Group>,
    )

    data class Group(
        val id: String,
        val members: List<Member>,
    )

    data class Member(
        val loginId: String,
        val status: String,
        val progress: Int,
        /** 첫 문제은행 도착(게임 날짜 KST). 시작 전이면 null. */
        val startedAt: LocalTime?,
        val lastSolvedAt: LocalTime?,
        val passNo: Int?,
        val expectedRank: Int,
        /** 표시 순서(1부터). */
        val expectedOrder: Int,
        /** 표가 적은 지각 여부 — [late] 계산이 표와 같은지 확인하는 데 쓴다. */
        val expectedLate: Boolean,
        val note: String,
    )

    val file: File by lazy {
        val json = requireNotNull(javaClass.getResourceAsStream("/ranking-cases.json")) { "ranking-cases.json 없음" }
        json.use { jacksonObjectMapper().readValue<File>(it) }
    }

    val groups: List<Group> get() = file.groups

    /** 지각: 시작 시각 > 07:00:03.000000(D8 — 정각은 지각 아님). */
    fun late(member: Member): Boolean = member.startedAt?.isAfter(file.lateAfter) ?: false

    /** 게임 날짜 KST 시각 → 에포크 마이크로초. */
    fun epochMicros(time: LocalTime): Long {
        val instant = file.gameDate.atTime(time).atZone(ZoneId.of(file.zone)).toInstant()
        return Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1_000L)
    }

    fun key(member: Member): RankingKey =
        RankingKey(
            loginId = LoginId(member.loginId),
            cheatFailed = member.status == "CHEAT_FAILED",
            progress = member.progress,
            lastSolvedAtUs = member.lastSolvedAt?.let(::epochMicros),
            passNo = member.passNo,
            late = late(member),
        )
}
