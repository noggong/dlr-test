package com.delightroom.mission.core.domain.group

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.user.User
import java.time.Instant

@JvmInline
value class GroupId(val value: Long)

/** 유일한 그룹명(R-G2). 비교 키 = 앞뒤 공백 제거 + 영문 소문자(prd D11). */
@JvmInline
value class GroupName(val value: String)

class Group(
    val id: GroupId,
    val name: GroupName,
    val members: List<User>,
) {
    /** 정원(최대 10명) 안에서 새 유저를 받을 수 있는가(R-G3). 정원은 전체 소속 인원(prd D12). */
    fun canAccept(maxSize: Int): Boolean = throw NotImplementedYet("auth-signup")

    /** 그날의 그룹원 — 07:00 이전 가입자(R-G5). */
    fun rosterOn(gameStartAt: Instant): List<User> = throw NotImplementedYet("game-session")
}
