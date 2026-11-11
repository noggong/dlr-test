package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.progress.ShownWrite
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** `daily_progress` · `served_questions`(LLD game-session §6). 쿼리 id 는 LLD 표와 같다. Q11 · Q12 는 문장 하나라 따로 트랜잭션을 열지 않는다. */
internal class RdbDailyProgressStore(
    private val db: DatabaseClient,
) : DailyProgressStore {
    /** Q10 — 그날 파티션이 없으면 실패한다(파티션은 기동 · `worker: member` 가 만든다). */
    override suspend fun insertNotStarted(date: LocalDate, members: List<Pair<UserId, GroupId>>): Long {
        if (members.isEmpty()) return 0
        return db.sql(
            "INSERT INTO daily_progress (game_date, user_id, group_id, progress, status) " +
                "SELECT :date, u, g, -1, 'NOT_STARTED' FROM unnest(CAST(:uids AS bigint[]), CAST(:gids AS bigint[])) AS t(u, g) " +
                "ON CONFLICT (game_date, user_id) DO NOTHING",
        )
            .bind("date", date)
            .bind("uids", members.map { it.first.value }.toTypedArray())
            .bind("gids", members.map { it.second.value }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /**
     * Q9 — 이름 `<테이블>_YYYYMMDD` · 범위 `[date, date+1)`(schema.sql 의 기동 때 파티션과 같은 꼴).
     * 이름 · 값은 [LocalDate] 에서만 나오므로 문자열로 이어 붙여도 주입 여지가 없다(DDL 은 바인드 변수를 받지 않는다).
     */
    override suspend fun ensurePartitions(date: LocalDate) {
        val suffix = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        for (table in PARTITIONED) {
            db.sql(
                "CREATE TABLE IF NOT EXISTS ${table}_$suffix PARTITION OF $table " +
                    "FOR VALUES FROM ('$date') TO ('${date.plusDays(1)}')",
            ).then().awaitSingleOrNull()
        }
    }

    /**
     * Q11 — 행이 없으면(`worker: member` 가 오지 않은 그룹) 넣고, 있으면 -1 · `NOT_STARTED` 만 0 · `IN_PROGRESS` 로 올린다.
     * 진행도는 `GREATEST` 로 줄지 않고, 처음 시작 시각 · 지각은 `COALESCE` 로 덮지 않는다 — 재처리는 `updated_at` 만 바뀐다.
     */
    override suspend fun applyMissionStarted(record: MissionStartedRecord) {
        db.sql(
            "INSERT INTO daily_progress (game_date, user_id, group_id, progress, status, started_at, is_late, updated_at) " +
                "VALUES (:d, :u, :g, 0, 'IN_PROGRESS', :at, :late, now()) " +
                "ON CONFLICT (game_date, user_id) DO UPDATE SET progress = GREATEST(daily_progress.progress, 0), " +
                "status = CASE WHEN daily_progress.status = 'NOT_STARTED' THEN 'IN_PROGRESS' ELSE daily_progress.status END, " +
                "started_at = COALESCE(daily_progress.started_at, EXCLUDED.started_at), " +
                "is_late = COALESCE(daily_progress.is_late, EXCLUDED.is_late), updated_at = now()",
        )
            .bind("d", record.date).bind("u", record.userId.value).bind("g", record.groupId.value)
            .bind("at", odt(record.startedAt)).bind("late", record.late)
            .fetch().awaitRowsUpdated()
    }

    /**
     * Q12 — 바깥 `SELECT` 는 같은 문장의 `INSERT` 가 넣은 행을 보지 않는다(같은 스냅샷). 그래서 행이 나오면 = 이미 있던 행(충돌)이고,
     * 행이 없으면 = 이번에 넣었다. 같은 유저의 이벤트는 한 버킷에서 차례로 처리되므로 다른 트랜잭션과 겨루지 않는다.
     */
    override suspend fun recordShown(record: QuestionShownRecord): ShownWrite {
        val existing = db.sql(
            "WITH ins AS (INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                "VALUES (:u, :q, :d, :no, 'SHOWN', :at) ON CONFLICT (user_id, question_id) DO NOTHING RETURNING 1) " +
                "SELECT (SELECT count(*) FROM ins) AS inserted, s.game_date, s.question_no " +
                "FROM served_questions s WHERE s.user_id = :u AND s.question_id = :q",
        )
            .bind("u", record.userId.value).bind("q", record.questionId).bind("d", record.date)
            .bind("no", record.questionNo).bind("at", odt(record.shownAt))
            .map { r -> r.get("game_date", LocalDate::class.java)!! to r.get("question_no", Number::class.java)!!.toInt() }
            .awaitOneOrNull()
            ?: return ShownWrite.Inserted
        val (firstDate, firstNo) = existing
        return if (firstDate == record.date && firstNo == record.questionNo) ShownWrite.Replayed else ShownWrite.Duplicate(firstDate, firstNo)
    }

    private fun odt(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private companion object {
        val PARTITIONED = listOf("daily_progress", "solve_signals", "group_results", "group_result_members")
    }
}
