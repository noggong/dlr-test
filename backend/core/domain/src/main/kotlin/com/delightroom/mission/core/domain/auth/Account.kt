package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/** 로그인 · refresh 가 읽는 한 줄(users ⋈ groups — auth-signup LLD §4 Q5 · Q6). */
data class Account(
    val userId: UserId,
    val loginId: LoginId,
    val passwordHash: String,
    val groupId: GroupId,
    val groupName: GroupName,
) {
    override fun toString(): String = "Account(userId=$userId, loginId=$loginId, groupId=$groupId, groupName=$groupName)"
}

/** 가입 결과 — `groupName` 은 저장된 `groups.name` 이다(LLD §3.2). */
data class SignedUp(
    val loginId: LoginId,
    val groupName: GroupName,
    val joinedAt: Instant,
)
