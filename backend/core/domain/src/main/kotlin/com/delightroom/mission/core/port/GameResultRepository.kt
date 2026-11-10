package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.group.GroupId
import java.time.LocalDate

/** 확정된 그룹 결과 보관. 한 (그룹, 날짜)에 한 번만 기록된다(NFR-2). */
interface GameResultRepository {
    suspend fun find(groupId: GroupId, date: LocalDate): GroupResult?

    /** 이미 기록돼 있으면 false. */
    suspend fun recordOnce(result: GroupResult): Boolean
}
