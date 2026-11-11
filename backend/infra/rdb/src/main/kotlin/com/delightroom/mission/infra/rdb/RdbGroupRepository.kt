package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.port.GroupRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull

internal class RdbGroupRepository(
    private val db: DatabaseClient,
) : GroupRepository {
    override suspend fun findById(id: GroupId): Group? = TODO("auth-signup")

    override suspend fun findByName(name: GroupName): Group? = TODO("auth-signup")

    override suspend fun create(name: GroupName): Group = TODO("auth-signup")

    /** LLD game-session §6 Q6. */
    override suspend fun findName(id: GroupId): GroupName? =
        db.sql("SELECT name FROM groups WHERE id = :id")
            .bind("id", id.value)
            .map { row -> GroupName(row.get("name", String::class.java)!!) }
            .awaitOneOrNull()
}
