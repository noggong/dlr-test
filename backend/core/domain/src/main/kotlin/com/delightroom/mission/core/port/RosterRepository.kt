package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupRestore
import java.time.LocalDate

/** Redis 그날 명단(LLD game-session §2.1 · §4.1). */
interface RosterRepository {
    /** 명단 Hash — `#size` 가 없으면(아직 확정 전) `null`. */
    suspend fun find(gid: GroupId, date: LocalDate): DayRoster?

    /**
     * `roster.lua` — 명단이 없을 때만 명단 Hash 를 만들고, 2명 이상이면 그룹 Hash(없을 때만) · 그날 그룹 목록에 넣는다.
     * 이번 호출이 만들었으면 `true`(ORACLE I4 — 한 번만 `true`).
     * [restore] 가 있으면 그룹 Hash 를 만들 때 그 칸 · `version` 으로 덮는다(재적재 — LLD platform §4.1 `roster.lua` 꼬리).
     */
    suspend fun createIfAbsent(roster: DayRoster, restore: GroupRestore?): Boolean

    /** 버킷 표시 `rk:{<b>}:<date>` 를 모든 버킷에 쓴다 — [date] 명단 확정이 끝났다(LLD platform §4.1 감시). 만료는 명단과 같다. */
    suspend fun markConfirmed(date: LocalDate)

    /** [date] 의 버킷 표시가 없는 버킷 수 — 0 이면 다 있다. */
    suspend fun countUnmarked(date: LocalDate): Int
}
