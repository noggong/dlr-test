package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/** 그날의 진행도 — 핫 경로. 원자성 · 멱등(NFR-3)은 구현이 지킨다. */
interface ProgressRepository {
    suspend fun find(userId: UserId, date: LocalDate): MissionProgress?

    suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress>

    suspend fun save(progress: MissionProgress): MissionProgress

    /**
     * 미션 시작(LLD game-session §4.2 `start.lua`) — 그날 [date] 의 시간 창 · 명단 · 그룹원 · 1명 그룹을 판정하고, 처음이면
     * 진행도 0 · 시작 시각 · 지각을 고정하고 `mission.started` 를 이벤트 Stream · 그룹 채널에 낸다. 다시 부르면 바꾸지 않는다.
     */
    suspend fun startMission(userId: UserId, groupId: GroupId, date: LocalDate): MissionStartOutcome
}
