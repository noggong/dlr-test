package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName

interface GroupRepository {
    suspend fun findById(id: GroupId): Group?

    suspend fun findByName(name: GroupName): Group?

    suspend fun create(name: GroupName): Group

    /** 그룹명 — LLD game-session §6 Q6. */
    suspend fun findName(id: GroupId): GroupName?

    /** 그룹 페이지 — LLD game-session §6 Q7(`worker: member`). PK 오름차순, [afterId] 보다 큰 것부터 [limit] 개(`null` 이면 처음부터). */
    suspend fun findPage(afterId: GroupId?, limit: Int): List<Pair<GroupId, GroupName>>
}
