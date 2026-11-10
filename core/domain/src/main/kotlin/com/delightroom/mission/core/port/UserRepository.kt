package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId

interface UserRepository {
    suspend fun findById(id: UserId): User?

    suspend fun findByLoginId(loginId: LoginId): User?

    suspend fun save(user: User): User
}
