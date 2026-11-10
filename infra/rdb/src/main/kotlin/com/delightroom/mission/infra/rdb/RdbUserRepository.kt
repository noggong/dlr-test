package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.UserRepository

internal class RdbUserRepository : UserRepository {
    override suspend fun findById(id: UserId): User? = TODO("auth-signup")

    override suspend fun findByLoginId(loginId: LoginId): User? = TODO("auth-signup")

    override suspend fun save(user: User): User = TODO("auth-signup")
}
