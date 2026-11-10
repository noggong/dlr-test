package com.delightroom.mission.app.api.dto

import java.time.Instant
import java.time.LocalDate

data class GroupMemberResponse(
    val userId: String,
)

data class GameGroupResponse(
    val name: String,
    /** 그날의 그룹원 — 07:00 이전 가입자(R-G5). */
    val members: List<GroupMemberResponse>,
)

/** 게임 정보(FR-3). */
data class GameInfoResponse(
    val date: LocalDate,
    val startAt: Instant,
    val deadlineAt: Instant,
    val serverTime: Instant,
    val group: GameGroupResponse,
)

data class QuestionResponse(
    val questionId: String,
    val difficulty: Int,
    /** 정답 해시만 내려준다(R-A3). 함수 · salt 는 Q5. */
    val answerHash: String,
)

/** 문제은행(R-B1 ~ R-B3). 받은 순간이 미션 시작이다. */
data class QuestionBankResponse(
    val missionStartedAt: Instant,
    val questions: List<QuestionResponse>,
)
