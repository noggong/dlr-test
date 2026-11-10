package com.delightroom.mission.core.domain.group

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.user.User
import java.time.Instant

@JvmInline
value class GroupId(val value: Long)

/** 유일한 그룹명(R-G2). 비교 규칙은 열린 질문 Q10. */
@JvmInline
value class GroupName(val value: String)

class Group(
    val id: GroupId,
    val name: GroupName,
    val members: List<User>,
) {
    /** 정원(최대 10명) 안에서 새 유저를 받을 수 있는가(R-G3). 정원 기준은 Q11. */
    fun canAccept(maxSize: Int): Boolean = throw NotImplementedYet("auth-signup")

    /** 그날의 그룹원 — 07:00 이전 가입자(R-G5). */
    fun rosterOn(gameStartAt: Instant): List<User> = throw NotImplementedYet("game-session")
}
