package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/** PostgreSQL `daily_progress`(LLD game-session §6). 나머지 쿼리(Q11 · Q12)는 쓰는 일감이 더한다. */
interface DailyProgressStore {
    /** Q10 — 그날 그룹원의 시작 전(-1 · `NOT_STARTED`) 행. 이미 있으면 그대로(`ON CONFLICT DO NOTHING`). 넣은 행 수. */
    suspend fun insertNotStarted(date: LocalDate, members: List<Pair<UserId, GroupId>>): Long

    /** Q9 — [date] 하루의 파티션 4개(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`). 이미 있으면 그대로. */
    suspend fun ensurePartitions(date: LocalDate)
}
