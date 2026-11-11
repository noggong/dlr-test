package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/** 로그인 · refresh 가 읽는 계정 한 줄(users ⋈ groups — LLD auth-signup §4 Q5 · Q6). */
class Account(
    val userId: UserId,
    val loginId: LoginId,
    val passwordHash: String,
    val groupId: GroupId,
    val groupName: GroupName,
)

/** 가입 결과 — [groupName] 은 저장된 표기(EXISTING 이면 그룹을 만든 사람의 표기), [joinedAt] 은 DB 시계. */
data class SignedUp(
    val loginId: LoginId,
    val groupName: GroupName,
    val joinedAt: Instant,
)
