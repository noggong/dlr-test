package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.progress.ShownWrite
import com.delightroom.mission.core.domain.roster.GroupDayRecord
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/** PostgreSQL `daily_progress` · `served_questions`(LLD game-session §6 Q9 ~ Q12). 메서드 하나 = 문장 하나 · 멱등. */
interface DailyProgressStore {
    /** Q10 — 그날 그룹원의 시작 전(-1 · `NOT_STARTED`) 행. 이미 있으면 그대로(`ON CONFLICT DO NOTHING`). 넣은 행 수. */
    suspend fun insertNotStarted(date: LocalDate, members: List<Pair<UserId, GroupId>>): Long

    /** Q9 — [date] 하루의 파티션 4개(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`). 이미 있으면 그대로. */
    suspend fun ensurePartitions(date: LocalDate)

    /** Q11 — `mission.started` 를 진행도 0 · `IN_PROGRESS` · 시작 시각 · 지각으로 멱등 upsert. 진행도 · 끝난 상태 · 처음 시작 시각은 되돌리지 않는다. */
    suspend fun applyMissionStarted(record: MissionStartedRecord)

    /** Q12 — `served_questions` 에 `SHOWN` 행. 이미 있으면 그대로 두고 그 행의 (날짜, 번호)로 재처리 · 중복을 가른다. */
    suspend fun recordShown(record: QuestionShownRecord): ShownWrite

    /**
     * 재적재(LLD platform §4.1 R1 · R2) — [gids] 그룹의 [date] 기록. 행도 결과도 없는 그룹은 빠진다(처음 만드는 명단).
     */
    suspend fun loadDayStates(date: LocalDate, gids: List<GroupId>): Map<GroupId, GroupDayRecord>
}
