package com.delightroom.mission.core.domain.user

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.group.GroupId
import java.time.Instant

@JvmInline
value class UserId(val value: Long)

/** 로그인 ID. 현황판에 그룹원 ID 로 보인다(prd §2 현황판). */
@JvmInline
value class LoginId(val value: String)

class User(
    val id: UserId,
    val loginId: LoginId,
    val passwordHash: String,
    val groupId: GroupId,
    val joinedAt: Instant,
) {
    /** 그 날짜 07:00 이전 가입자만 그날 게임에 참가한다(R-G4). */
    fun isParticipantOn(gameStartAt: Instant): Boolean = throw NotImplementedYet("auth-signup")
}
