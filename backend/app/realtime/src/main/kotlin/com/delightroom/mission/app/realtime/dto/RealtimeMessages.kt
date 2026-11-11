package com.delightroom.mission.app.realtime.dto

import com.delightroom.mission.core.domain.result.MICROS_ISO_PATTERN
import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * SSE 로 내려가는 메시지(realtime-board SPEC §6). `event:` 이름은 [RealtimeEvent], `data:` 는 아래 data class 의 JSON 한 줄,
 * `id:` 는 `data.version` 이다(RB15). 기계가 읽는 정의는 docs/api/asyncapi.yaml. 하트비트(`: ping`)는 메시지가 아니라 주석 줄이다.
 */
object RealtimeEvent {
    /** 연결마다 첫 메시지 — 보는 사람 기준 현황판(RB5). */
    const val BOARD_SNAPSHOT = "board.snapshot"

    /** 결과가 없을 때 그룹원 한 명의 줄이 바뀜 — 바뀐 뒤 현황판 전체. */
    const val MEMBER_PROGRESSED = "member.progressed"

    /** 08:00 마감 1차에서 그룹 확정이 08:00:03 까지 미뤄짐(D17). */
    const val GROUP_PENDING = "group.pending"

    /** 그룹 결과 확정 · 08:00 확정 그룹의 잠정 랭킹 변경. */
    const val GROUP_RESULT = "group.result"

    /** 08:00:03 마감 2차의 랭킹 확정 — 이 뒤 스트림을 닫는다(RB8). */
    const val GROUP_RANKING_FINAL = "group.ranking_final"
}

/** 모든 메시지는 그룹 Hash 버전 [version] 을 가진다 — 클라이언트는 연결마다 이하 값을 버린다(RB6). */
sealed interface RealtimeMessage {
    val version: Long
    val date: LocalDate
    val groupStatus: String
}

/** 현황판 · 결과 랭킹 · SSE 의 한 줄(MemberEntry). 필드 7개를 언제나 싣는다(null 은 null 로). */
data class MemberEntry(
    val userId: String,
    /** 경쟁 순위(1, 1, 3). 부정 실패는 결과 확정 전에도 맨 아래(D2). */
    val rank: Int,
    /** 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val progress: Int,
    /** MemberStatus — 보는 사람별 규칙 RB10. */
    val status: String,
    /** 미션 시작 여부 — false 면 「미접속」(D13). */
    val started: Boolean,
    /** 마지막 풀이 신호의 서버 도착 시각(마이크로초 6자리 — 현황판 조회와 같은 바이트, RB12), 푼 문제가 없으면 null. */
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val lastSolvedAt: OffsetDateTime?,
    /** 패스를 쓴 문제 번호, 안 썼으면 null. */
    val passedAtQuestionNo: Int?,
)

/** `board.snapshot` — groupStatus 는 GroupStatus 4개 모두 가능. */
data class BoardSnapshotMessage(
    override val version: Long,
    override val date: LocalDate,
    override val groupStatus: String,
    val entries: List<MemberEntry>,
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val sentAt: OffsetDateTime,
) : RealtimeMessage

/** `member.progressed` — groupStatus 는 IN_PROGRESS · PENDING. */
data class MemberProgressedMessage(
    override val version: Long,
    override val date: LocalDate,
    override val groupStatus: String,
    /** 바뀐 그룹원(토스트 표시용). */
    val userId: String,
    val entries: List<MemberEntry>,
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val occurredAt: OffsetDateTime,
) : RealtimeMessage

/** `group.pending` — groupStatus 는 PENDING 만. 현황판은 싣지 않는다. */
data class GroupPendingMessage(
    override val version: Long,
    override val date: LocalDate,
    override val groupStatus: String,
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val occurredAt: OffsetDateTime,
) : RealtimeMessage

/** `group.result` — groupStatus 는 SUCCEEDED · FAILED. rankingFinal 이 true 면 이 뒤 스트림이 끝난다. */
data class GroupResultMessage(
    override val version: Long,
    override val date: LocalDate,
    override val groupStatus: String,
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val decidedAt: OffsetDateTime,
    /** 08:00 전 확정은 true, 08:00 이후 확정은 08:00:03 까지 false(잠정). */
    val rankingFinal: Boolean,
    val entries: List<MemberEntry>,
) : RealtimeMessage

/** `group.ranking_final` — rankingFinal 은 언제나 true. decidedAt 은 결과가 확정된 시각(이 메시지 시각이 아님). */
data class GroupRankingFinalMessage(
    override val version: Long,
    override val date: LocalDate,
    override val groupStatus: String,
    @field:JsonFormat(pattern = MICROS_ISO_PATTERN)
    val decidedAt: OffsetDateTime,
    val rankingFinal: Boolean,
    val entries: List<MemberEntry>,
) : RealtimeMessage
