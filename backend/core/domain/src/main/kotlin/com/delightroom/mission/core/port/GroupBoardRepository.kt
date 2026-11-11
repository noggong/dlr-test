package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/** 그날 그룹의 현황판 읽기(LLD realtime-board §1 · §3) — 판정과 읽기를 Lua 한 번에. */
interface GroupBoardRepository {
    suspend fun read(groupId: GroupId, date: LocalDate, userId: UserId, mode: BoardReadMode): BoardRead
}
