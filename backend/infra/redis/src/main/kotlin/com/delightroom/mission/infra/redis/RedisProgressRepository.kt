package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.ProgressRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.LocalDate

internal class RedisProgressRepository(
    private val redis: ReactiveStringRedisTemplate,
) : ProgressRepository {
    override suspend fun find(userId: UserId, date: LocalDate): MissionProgress? = TODO("solve-signal")

    override suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress> = TODO("realtime-board")

    override suspend fun save(progress: MissionProgress): MissionProgress = TODO("solve-signal")
}
