package com.delightroom.mission.app.api.dto

import com.delightroom.mission.core.domain.board.Board
import com.delightroom.mission.core.domain.board.BoardEntry
import com.delightroom.mission.core.domain.result.MICROS_ISO_PATTERN
import com.delightroom.mission.core.domain.result.RankedMember
import com.delightroom.mission.core.service.ResultView
import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** MemberEntry — 현황판 · 결과 랭킹 · SSE 의 한 줄(realtime-board SPEC §2). 필드 7개를 언제나 싣는다(null 은 null 로). */
data class MemberEntryResponse(
    /** 로그인 아이디(prd D5). */
    val userId: String,
    /** 경쟁 순위(1, 1, 3). */
    val rank: Int,
    /** 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val progress: Int,
    /** MemberStatus — 보는 사람별 규칙 RB10. */
    val status: String,
    /** 미션 시작 여부 — false 면 「미접속」(D13). */
    val started: Boolean,
    /** 마지막 풀이 신호의 서버 도착 시각(마이크로초 6자리 — 기본 직렬화는 끝의 0 을 지운다), 푼 문제가 없으면 null. */
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val lastSolvedAt: OffsetDateTime?,
    /** 패스를 쓴 문제 번호, 안 썼으면 null. */
    val passedAtQuestionNo: Int?,
) {
    companion object {
        /** 결과 랭킹 한 줄 — 내부 진행도 -1 은 `progress 0` · `started false`(SPEC MemberEntry). */
        fun of(member: RankedMember, zone: ZoneId): MemberEntryResponse =
            MemberEntryResponse(
                userId = member.loginId.value,
                rank = member.rank,
                progress = maxOf(member.progress, 0),
                status = member.status.name,
                started = member.progress >= 0,
                lastSolvedAt = member.lastSolvedAt?.let { offset(it, zone) },
                passedAtQuestionNo = member.passNo,
            )
    }
}

internal fun offset(at: Instant, zone: ZoneId): OffsetDateTime = at.atZone(zone).toOffsetDateTime()

/** `GET /api/v1/games/today/board` — 현황판(realtime-board SPEC §3). */
data class BoardResponse(
    val date: LocalDate,
    /** IN_PROGRESS · SUCCEEDED · FAILED(PENDING 은 08:00 이후라 오지 않는다). */
    val groupStatus: String,
    /** 그룹 Hash 버전 — SSE version 과 같은 축(RB6). */
    val version: Long,
    val entries: List<MemberEntryResponse>,
)

/** 시각을 마이크로초까지(SPEC realtime-board §2 — `2026-11-12T07:03:41.120384+09:00`). */
const val MICROS_DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX"

fun BoardEntry.toResponse(zone: ZoneId): MemberEntryResponse =
    MemberEntryResponse(
        userId = userId.value,
        rank = rank,
        progress = progress,
        status = status.name,
        started = started,
        lastSolvedAt = lastSolvedAt?.atZone(zone)?.toOffsetDateTime(),
        passedAtQuestionNo = passedAtQuestionNo,
    )

fun Board.toResponse(zone: ZoneId): BoardResponse =
    BoardResponse(date, groupStatus.name, version, entries.map { it.toResponse(zone) })

/**
 * `GET /api/v1/games/results/latest` — 결과 조회(deadline-result SPEC §2).
 * PENDING 이면 decidedAt null · rankingFinal false · myRank null · ranking [](DR2). 결과가 한 번도 없으면 404 NO_RESULT_YET.
 */
data class ResultResponse(
    val date: LocalDate,
    /** PENDING · SUCCEEDED · FAILED(IN_PROGRESS 는 주지 않는다). */
    val groupStatus: String,
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val decidedAt: OffsetDateTime?,
    val rankingFinal: Boolean,
    val myRank: Int?,
    val ranking: List<MemberEntryResponse>,
    val nextGameStartAt: OffsetDateTime,
) {
    companion object {
        fun of(view: ResultView, zone: ZoneId): ResultResponse =
            ResultResponse(
                date = view.result.date,
                groupStatus = view.result.groupStatus.name,
                decidedAt = view.result.decidedAt?.let { offset(it, zone) },
                rankingFinal = view.result.rankingFinal,
                myRank = view.myRank,
                ranking = view.result.ranking.map { MemberEntryResponse.of(it, zone) },
                nextGameStartAt = offset(view.nextGameStartAt, zone),
            )
    }
}
