package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.roster.DayRoster
import java.time.LocalDate

/** Redis 그날 명단(LLD game-session §2.1 · §4.1). */
interface RosterRepository {
    /** 명단 Hash — `#size` 가 없으면(아직 확정 전) `null`. */
    suspend fun find(gid: GroupId, date: LocalDate): DayRoster?

    /**
     * `roster.lua` — 명단이 없을 때만 명단 Hash 를 만들고, 2명 이상이면 그룹 Hash(없을 때만) · 그날 그룹 목록에 넣는다.
     * 이번 호출이 만들었으면 `true`(ORACLE I4 — 한 번만 `true`).
     */
    suspend fun createIfAbsent(roster: DayRoster): Boolean
}
