package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineOutcome
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.domain.result.GroupResultState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 그날 그룹 Hash 쪽 포트(LLD deadline-result §1) — 결과 상태 조회(T-DR-5) · 마감 처리(T-DR-3) · 버킷 순회(T-DR-4). */
interface GroupDayRepository {
    /** 그룹 [gid] 의 [date] Hash 결과 칸(`HMGET` 한 번 — LLD §3.3 1 · 2). Hash 가 없으면 null. */
    suspend fun findResultState(gid: GroupId, date: LocalDate): GroupResultState?

    /** 그룹 [gid] 의 [date] 게임을 [phase] 로 마감 처리한다(Lua `deadline` 한 번 — 멱등). */
    suspend fun runDeadline(gid: GroupId, date: LocalDate, phase: DeadlinePhase): DeadlineOutcome

    /** 버킷 [bucket] 의 [date] 그룹 목록(`day:{<b>}:<date>` 을 `SSCAN` 으로 끝까지). 같은 gid 가 두 번 나올 수 있다(SSCAN 성질). */
    fun scanDayGroups(bucket: Int, date: LocalDate): Flow<GroupId>
}
