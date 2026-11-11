package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** `daily_progress`(LLD game-session §6). 쿼리 id 는 LLD 표와 같다. */
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

    private companion object {
        val PARTITIONED = listOf("daily_progress", "solve_signals", "group_results", "group_result_members")
    }
}
