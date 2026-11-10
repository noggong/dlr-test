package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.GameResultRepository
import java.time.LocalDate

internal class RdbGameResultRepository : GameResultRepository {
    override suspend fun find(groupId: GroupId, date: LocalDate): GroupResult? = TODO("deadline-result")

    override suspend fun recordOnce(result: GroupResult): Boolean = TODO("deadline-result")
}
