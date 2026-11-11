package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.FailCommand
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.progress.PassCommand
import com.delightroom.mission.core.domain.progress.SignalReply
import com.delightroom.mission.core.domain.progress.SolveCommand
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/** 그날의 진행도 — 핫 경로. 원자성 · 멱등(NFR-3)은 구현이 지킨다. */
interface ProgressRepository {
    suspend fun find(userId: UserId, date: LocalDate): MissionProgress?

    suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress>

    suspend fun save(progress: MissionProgress): MissionProgress

    /** 풀이 신호 — Lua `solve` 한 번(LLD solve-signal §3.1). 판정 · 반영 · 그룹 판정 · 기록 · 전달이 한 실행이다. */
    suspend fun applySolve(command: SolveCommand): SignalReply

    /** 패스 신호 — Lua `pass`(§3.2). 받아들이면 [com.delightroom.mission.core.domain.progress.SignalResult.passedAtQuestionNo] 를 채운다. */
    suspend fun applyPass(command: PassCommand): SignalReply

    /** 부정 실패 신호 — Lua `fail`(§3.3). */
    suspend fun applyCheatFail(command: FailCommand): SignalReply

    /**
     * 미션 시작(LLD game-session §4.2 `start.lua`) — 그날 [date] 의 시간 창 · 명단 · 그룹원 · 1명 그룹을 판정하고, 처음이면
     * 진행도 0 · 시작 시각 · 지각을 고정하고 `mission.started` 를 이벤트 Stream · 그룹 채널에 낸다. 다시 부르면 바꾸지 않는다.
     */
    suspend fun startMission(userId: UserId, groupId: GroupId, date: LocalDate): MissionStartOutcome
}
