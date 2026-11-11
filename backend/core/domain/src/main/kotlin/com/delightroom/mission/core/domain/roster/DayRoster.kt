package com.delightroom.mission.core.domain.roster

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/**
 * 그날 명단(LLD game-session §2.2 명단 Hash) — 그 날짜 07:00 전 가입한 그룹원(SPEC GS3).
 * 0 · 1 명도 명단이다(1 = 1명 그룹 — GS4).
 */
class DayRoster(
    val gid: GroupId,
    val date: LocalDate,
    val name: GroupName,
    val members: Map<UserId, LoginId>,
) {
    val size: Int get() = members.size

    /** 그날 그룹원이 1명 — 문제은행 · 진입 신호를 `SOLO_GROUP` 으로 거부한다(GS4). */
    val isSolo: Boolean get() = members.size == 1

    fun contains(uid: UserId): Boolean = uid in members

    /** 로그인 아이디 오름차순(SPEC §2.1 `group.members` — 문자 코드 순서). */
    fun sortedLoginIds(): List<LoginId> = members.values.sortedBy { it.value }
}
