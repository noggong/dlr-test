package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/** 그날의 진행도 — 핫 경로. 원자성 · 멱등(NFR-3)은 구현이 지킨다. */
interface ProgressRepository {
    suspend fun find(userId: UserId, date: LocalDate): MissionProgress?

    suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress>

    suspend fun save(progress: MissionProgress): MissionProgress
}
