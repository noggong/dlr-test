package com.delightroom.mission.app.realtime.dto

import java.time.Instant
import java.time.LocalDate

/**
 * SSE 로 내려가는 메시지. `event:` 이름은 [RealtimeEvent], `data:` 는 아래 data class 의 JSON 이다.
 * 기계가 읽는 정의는 docs/api/asyncapi.yaml.
 */
object RealtimeEvent {
    /** 연결 · 재연결 직후 현재 현황판(R-R4). */
    const val BOARD_SNAPSHOT = "board.snapshot"

    /** 그룹원 진행도 변경(R-R2). */
    const val MEMBER_PROGRESSED = "member.progressed"

    /** 그룹 결과 확정(R-R3). */
    const val GROUP_RESULT = "group.result"
}

sealed interface RealtimeMessage

data class MemberEntry(
    val userId: String,
    /** 0~3 — 진행도 -1 은 0 으로(R-R1). */
    val progress: Int,
    val status: String,
    val rank: Int,
)

data class BoardSnapshotMessage(
    val date: LocalDate,
    val groupStatus: String,
    val entries: List<MemberEntry>,
    val sentAt: Instant,
) : RealtimeMessage

data class MemberProgressedMessage(
    val userId: String,
    val progress: Int,
    val status: String,
    val occurredAt: Instant,
) : RealtimeMessage

data class GroupResultMessage(
    val date: LocalDate,
    val groupStatus: String,
    val decidedAt: Instant,
    val ranking: List<MemberEntry>,
) : RealtimeMessage
