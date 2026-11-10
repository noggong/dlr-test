package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName

interface GroupRepository {
    suspend fun findById(id: GroupId): Group?

    suspend fun findByName(name: GroupName): Group?

    suspend fun create(name: GroupName): Group
}
