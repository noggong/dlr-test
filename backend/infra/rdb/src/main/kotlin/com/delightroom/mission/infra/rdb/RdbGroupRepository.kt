package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.port.GroupRepository

internal class RdbGroupRepository : GroupRepository {
    override suspend fun findById(id: GroupId): Group? = TODO("auth-signup")

    override suspend fun findByName(name: GroupName): Group? = TODO("auth-signup")

    override suspend fun create(name: GroupName): Group = TODO("auth-signup")
}
