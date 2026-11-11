package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate

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
}
