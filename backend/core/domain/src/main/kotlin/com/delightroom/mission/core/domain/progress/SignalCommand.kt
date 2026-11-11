package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant
import java.time.LocalDate

/** 풀이 신호 본문(R-S1 · SPEC §2) — 형식 검사를 마친 값. [successTime] 은 받은 ISO 문자열 그대로(기록만 — R-S5). */
data class SolveSignal(
    val questionNo: Int,
    val questionId: QuestionId,
    val successTime: String,
    val clickedAt: Instant,
)

/** 패스 신호 본문(R-P2). */
data class PassSignal(
    val questionNo: Int,
    val questionId: QuestionId,
)

/**
 * 신호 Lua 공통 입력(LLD §3.1 ARGV[1] ~ ARGV[6]) — 누가 · 어느 그룹 · 어느 날짜, 그 날짜의 시간 창(epoch µs).
 * 버킷은 키를 만드는 쪽(`RedisKeys`)이 gid 로 정한다. 판정은 Lua 가 Redis `TIME` 으로 한다.
 */
data class SignalTarget(
    val userId: UserId,
    val groupId: GroupId,
    val date: LocalDate,
    /** 07:00:00. */
    val openAtUs: Long,
    /** 08:00:00. */
    val deadlineAtUs: Long,
    /** 08:00:03 — 풀이 유예 끝(D1). */
    val graceEndAtUs: Long,
)

/** Lua `solve` 입력(LLD §3.1). */
data class SolveCommand(val target: SignalTarget, val signal: SolveSignal)

/** Lua `pass` 입력(LLD §3.2). */
data class PassCommand(val target: SignalTarget, val signal: PassSignal)

/** Lua `fail` 입력(LLD §3.3). */
data class FailCommand(val target: SignalTarget)
