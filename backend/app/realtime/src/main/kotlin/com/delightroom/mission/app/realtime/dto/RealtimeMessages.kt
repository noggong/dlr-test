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
    /** 미션 시작 여부 — false 면 진행도 -1(표기 0)(prd D5). */
    val started: Boolean? = null,
    /** 마지막 풀이 신호의 서버 도착 시각, 푼 문제가 없으면 null(R-S5 · prd D5). */
    val lastSolvedAt: Instant? = null,
    /** 패스를 쓴 문제 번호, 안 썼으면 null(R-P4 · prd D5). */
    val passedAtQuestionNo: Int? = null,
    /** 부정 실패 여부 — 다른 그룹원에게는 그룹 결과 확정 뒤에만 true(prd D2 · D5). */
    val cheatFailed: Boolean? = null,
)

data class BoardSnapshotMessage(
    val date: LocalDate,
    val groupStatus: String,
    val entries: List<MemberEntry>,
    val sentAt: Instant,
) : RealtimeMessage

/** 그룹원 진행도 · 상태 · 패스 변경 — 순위가 함께 바뀌므로 바뀐 뒤의 현황판 전체를 싣는다. */
data class MemberProgressedMessage(
    /** 바뀐 그룹원(로그인 아이디). */
    val userId: String,
    val date: LocalDate,
    val groupStatus: String,
    val entries: List<MemberEntry>,
    val occurredAt: Instant,
) : RealtimeMessage

data class GroupResultMessage(
    val date: LocalDate,
    val groupStatus: String,
    val decidedAt: Instant,
    val ranking: List<MemberEntry>,
) : RealtimeMessage
