package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.board.GroupResultCode
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.result.RankedMember
import com.delightroom.mission.core.domain.result.toGroupStatus
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.port.GameResultRepository
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import java.time.LocalDate
import java.time.OffsetDateTime

/** group_results · group_result_members — 지난 결과 조회(LLD deadline-result §2.3). 쓰기(`recordOnce`)는 persister 몫이다. */
internal class RdbGameResultRepository(
    private val db: DatabaseClient,
) : GameResultRepository {
    override suspend fun findLatest(gid: GroupId): LatestResult? {
        val head = db.sql(
            "SELECT game_date, result, completed_at FROM group_results " +
                "WHERE group_id = :gid ORDER BY game_date DESC LIMIT 1",
        )
            .bind("gid", gid.value)
            .map { row ->
                Head(
                    date = row.get("game_date", LocalDate::class.java)!!,
                    result = GroupResultCode.valueOf(row.get("result", String::class.java)!!),
                    completedAt = row.get("completed_at", OffsetDateTime::class.java)!!,
                )
            }
            .awaitOneOrNull()
            ?: return null
        val ranking = db.sql(
            "SELECT u.login_id, m.rank, m.progress, m.status, m.last_solved_at, m.pass_question_no " +
                "FROM group_result_members m JOIN users u ON u.id = m.user_id " +
                "WHERE m.game_date = :gameDate AND m.group_id = :gid " +
                "ORDER BY m.rank, u.login_id COLLATE \"C\"",
        )
            .bind("gameDate", head.date)
            .bind("gid", gid.value)
            .map(::member)
            .all()
            .collectList()
            .awaitSingle()
        return LatestResult(
            date = head.date,
            groupStatus = head.result.toGroupStatus(),
            decidedAt = head.completedAt.toInstant(),
            // 칸이 없다 — 시각으로 정하는 것은 BoardService(§2.3)
            rankingFinal = false,
            ranking = ranking,
        )
    }

    override suspend fun find(groupId: GroupId, date: LocalDate): GroupResult? = TODO("deadline-result")

    override suspend fun recordOnce(result: GroupResult): Boolean = TODO("deadline-result")

    private class Head(val date: LocalDate, val result: GroupResultCode, val completedAt: OffsetDateTime)

    private fun member(row: Readable): RankedMember =
        RankedMember(
            loginId = LoginId(row.get("login_id", String::class.java)!!),
            rank = row.int("rank")!!,
            progress = row.int("progress")!!,
            status = MemberStatus.valueOf(row.get("status", String::class.java)!!),
            lastSolvedAt = row.get("last_solved_at", OffsetDateTime::class.java)?.toInstant(),
            passNo = row.int("pass_question_no"),
        )

    private fun Readable.int(name: String): Int? = (get(name) as Number?)?.toInt()
}
