package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.progress.ShownWrite
import com.delightroom.mission.core.domain.roster.GroupDayRecord
import com.delightroom.mission.core.domain.roster.MemberDayRecord
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import io.r2dbc.spi.Row
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
     * 4개 테이블을 `DO` 블록 한 문장(= 한 트랜잭션)으로 만들고, 맨 앞에서 스키마 잠금([SchemaLock])을 잡는다 — schema.sql 적용 · 다른 worker 의
     * 파티션 생성과 차례를 선다(LLD platform §4.3). 스키마가 아직 없으면 `42P01` 로 실패한다(부르는 쪽이 다시 시도한다).
     * 이름 · 값은 [LocalDate] 에서만 나오므로 문자열로 이어 붙여도 주입 여지가 없다(DDL 은 바인드 변수를 받지 않는다).
     */
    override suspend fun ensurePartitions(date: LocalDate) {
        val suffix = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val ddl = PARTITIONED.joinToString(" ") { table ->
            "CREATE TABLE IF NOT EXISTS ${table}_$suffix PARTITION OF $table FOR VALUES FROM ('$date') TO ('${date.plusDays(1)}');"
        }
        db.sql("DO \$\$ BEGIN PERFORM pg_advisory_xact_lock(${SchemaLock.KEY}); $ddl END \$\$").then().awaitSingleOrNull()
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

    /**
     * 재적재 R1 · R2(LLD platform §4.2). 「지금 문제」는 그날 `SHOWN` 이고 번호 = 진행도 + 1 인 행 — 패스로 넘긴(`PASSED`) · 푼(`SOLVED`)
     * 문제는 고르지 않는다. 진행도는 `daily_progress` 와 `solve_signals` 의 큰 쪽으로 본다(해석은 `GroupRestore`).
     */
    override suspend fun loadDayStates(date: LocalDate, gids: List<GroupId>): Map<GroupId, GroupDayRecord> {
        if (gids.isEmpty()) return emptyMap()
        val ids = gids.map { it.value }.toTypedArray()
        val members = db.sql(
            "SELECT dp.group_id, dp.user_id, dp.progress, dp.status, dp.started_at, dp.is_late, dp.last_solved_at, " +
                "dp.pass_question_no, pq.pk_hash AS passed_pk, dp.cheat_failed_at, ss.question_no AS ss_no, ss.arrived_at AS ss_at, " +
                "cur.pk_hash AS cur_pk " +
                "FROM daily_progress dp " +
                "LEFT JOIN questions pq ON pq.id = dp.passed_question_id " +
                "LEFT JOIN LATERAL (SELECT question_no, arrived_at FROM solve_signals s " +
                "WHERE s.game_date = dp.game_date AND s.user_id = dp.user_id ORDER BY question_no DESC LIMIT 1) ss ON true " +
                "LEFT JOIN LATERAL (SELECT q.pk_hash FROM served_questions sq JOIN questions q ON q.id = sq.question_id " +
                "WHERE sq.user_id = dp.user_id AND sq.game_date = dp.game_date AND sq.kind = 'SHOWN' " +
                "AND sq.question_no = GREATEST(dp.progress, COALESCE(ss.question_no, -1)) + 1 " +
                "ORDER BY sq.served_at DESC LIMIT 1) cur ON true " +
                "WHERE dp.game_date = :d AND dp.group_id = ANY(:gids)",
        )
            .bind("d", date).bind("gids", ids)
            .map { r, _ -> GroupId(long(r, "group_id")) to memberRecord(r) }
            .all().collectList().awaitSingle()
        val results = db.sql("SELECT group_id, result, completed_at FROM group_results WHERE game_date = :d AND group_id = ANY(:gids)")
            .bind("d", date).bind("gids", ids)
            .map { r, _ -> GroupId(long(r, "group_id")) to (r.get("result", String::class.java)!! to instant(r, "completed_at")!!) }
            .all().collectList().awaitSingle().toMap()
        val byGroup = members.groupBy({ it.first }, { it.second })
        return (byGroup.keys + results.keys).associateWith { gid ->
            val result = results[gid]
            GroupDayRecord(byGroup[gid].orEmpty(), result?.first, result?.second)
        }
    }

    private fun memberRecord(r: Row): MemberDayRecord =
        MemberDayRecord(
            userId = UserId(long(r, "user_id")),
            progress = r.get("progress", Number::class.java)!!.toInt(),
            status = r.get("status", String::class.java)!!,
            startedAt = instant(r, "started_at"),
            late = r.get("is_late", java.lang.Boolean::class.java)?.booleanValue(),
            lastSolvedAt = instant(r, "last_solved_at"),
            passNo = r.get("pass_question_no", Number::class.java)?.toInt(),
            passedPk = r.get("passed_pk", String::class.java),
            cheatFailedAt = instant(r, "cheat_failed_at"),
            solvedNo = r.get("ss_no", Number::class.java)?.toInt(),
            solvedAt = instant(r, "ss_at"),
            currentPk = r.get("cur_pk", String::class.java),
        )

    private fun long(r: Row, name: String): Long = r.get(name, Number::class.java)!!.toLong()

    private fun instant(r: Row, name: String): Instant? = r.get(name, OffsetDateTime::class.java)?.toInstant()

    private fun odt(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private companion object {
        val PARTITIONED = listOf("daily_progress", "solve_signals", "group_results", "group_result_members")
    }
}
