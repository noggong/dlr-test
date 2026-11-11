package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.SignedUp
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId

interface UserRepository {
    suspend fun findById(id: UserId): User?

    suspend fun findByLoginId(loginId: LoginId): User?

    suspend fun save(user: User): User

    /**
     * 가입 — 그룹 만들기 · 인원 늘리기와 유저 만들기를 한 트랜잭션에서 한다(LLD auth-signup §3.2 · §4 Q1 ~ Q4).
     * 그룹 단계가 먼저다(SPEC AS8). 실패하면 롤백하고 `BusinessException` 을 던진다:
     * `GROUP_NAME_TAKEN` · `GROUP_NOT_FOUND` · `GROUP_FULL` · `LOGIN_ID_TAKEN`.
     */
    suspend fun signUp(loginId: LoginId, passwordHash: String, choice: GroupChoice, maxGroupSize: Int): SignedUp

    /** 로그인 — Q5. */
    suspend fun findAccountByLoginId(loginId: LoginId): Account?

    /** refresh — Q6. */
    suspend fun findAccountById(id: UserId): Account?
}
