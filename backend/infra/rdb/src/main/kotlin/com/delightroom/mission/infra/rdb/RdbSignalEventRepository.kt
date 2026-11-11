package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.board.GroupResultCode
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.progress.CheatFailRecord
import com.delightroom.mission.core.domain.progress.GroupDecidedRecord
import com.delightroom.mission.core.domain.progress.PassRecord
import com.delightroom.mission.core.domain.progress.ResultMemberRecord
import com.delightroom.mission.core.domain.progress.SolveRecord
import com.delightroom.mission.core.port.SignalEventRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * solve-signal 이벤트 → PostgreSQL(LLD solve-signal §2.3 · LLD platform §3). 메서드 하나 = 한 트랜잭션.
 *
 * 모두 멱등이다 — PK + `ON CONFLICT` · `progress < :no` · 상태 조건(`NOT_STARTED` · `IN_PROGRESS` 에서만) · `pass_question_no IS NULL`.
 * 같은 이벤트를 두 번 넘겨도 두 번째는 행을 바꾸지 않는다(`updated_at` 도 조건이 맞을 때만 바뀐다).
 */
internal class RdbSignalEventRepository(
    private val db: DatabaseClient,
    private val tx: TransactionalOperator,
) : SignalEventRepository {
    override suspend fun recordSolve(record: SolveRecord) {
        tx.executeAndAwait {
            val questionId = questionId(record.questionPk)
            db.sql(
                "INSERT INTO solve_signals (game_date, user_id, question_no, question_id, success_time, click_time, arrived_at) " +
                    "VALUES (:d, :u, :no, :q, :success, :click, :arrived) ON CONFLICT DO NOTHING",
            )
                .bind("d", record.date).bind("u", record.userId.value).bind("no", record.questionNo).bind("q", questionId)
                .bind("success", odt(record.successTime)).bind("click", odt(record.clickedAt))
                .bind("arrived", odt(record.arrivedAt))
                .fetch().awaitRowsUpdated()
            // 진행도가 이미 :no 이상이면(재처리) 아무 칸도 바뀌지 않게 WHERE 로 거른다.
            // NOT_STARTED 도 SUCCEEDED 로 — 미션 시작 이벤트가 늦게 처리된 경우(LLD platform §3.1 한계). FAILED(마감 뒤 유예 풀이)는 그대로
            db.sql(
                "UPDATE daily_progress SET progress = :no, last_solved_at = :arrived, " +
                    "status = CASE WHEN status IN ('NOT_STARTED', 'IN_PROGRESS') AND :no = 3 THEN 'SUCCEEDED' ELSE status END, " +
                    "updated_at = now() " +
                    "WHERE game_date = :d AND user_id = :u AND progress < :no",
            )
                .bind("no", record.questionNo).bind("arrived", odt(record.arrivedAt))
                .bind("d", record.date).bind("u", record.userId.value)
                .fetch().awaitRowsUpdated()
            db.sql("UPDATE served_questions SET kind = 'SOLVED' WHERE user_id = :u AND question_id = :q AND kind <> 'SOLVED'")
                .bind("u", record.userId.value).bind("q", questionId)
                .fetch().awaitRowsUpdated()
        }
    }

    override suspend fun recordPass(record: PassRecord) {
        tx.executeAndAwait {
            val questionId = questionId(record.questionPk)
            db.sql(
                "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                    "VALUES (:u, :q, :d, :no, 'PASSED', :at) " +
                    "ON CONFLICT (user_id, question_id) DO UPDATE SET kind = 'PASSED' WHERE served_questions.kind <> 'PASSED'",
            )
                .bind("u", record.userId.value).bind("q", questionId).bind("d", record.date)
                .bind("no", record.questionNo).bind("at", odt(record.at))
                .fetch().awaitRowsUpdated()
            db.sql(
                "UPDATE daily_progress SET pass_question_no = :no, passed_question_id = :q, updated_at = now() " +
                    "WHERE game_date = :d AND user_id = :u AND pass_question_no IS NULL",
            )
                .bind("no", record.questionNo).bind("q", questionId)
                .bind("d", record.date).bind("u", record.userId.value)
                .fetch().awaitRowsUpdated()
        }
    }

    override suspend fun recordCheatFail(record: CheatFailRecord) {
        tx.executeAndAwait {
            db.sql(
                "UPDATE daily_progress SET status = 'CHEAT_FAILED', cheat_failed_at = :at, updated_at = now() " +
                    "WHERE game_date = :d AND user_id = :u AND status IN ('NOT_STARTED', 'IN_PROGRESS')",
            )
                .bind("at", odt(record.at)).bind("d", record.date).bind("u", record.userId.value)
                .fetch().awaitRowsUpdated()
        }
    }

    override suspend fun recordGroupDecided(record: GroupDecidedRecord): Boolean =
        tx.executeAndAwait {
            val inserted = db.sql(
                "INSERT INTO group_results (game_date, group_id, result, completed_at, member_count) " +
                    "VALUES (:d, :g, :result, :at, :count) ON CONFLICT DO NOTHING",
            )
                .bind("d", record.date).bind("g", record.groupId.value).bind("result", record.result.name)
                .bind("at", odt(record.decidedAt)).bind("count", record.memberCount)
                .fetch().awaitRowsUpdated() == 1L
            // 결과는 정확히 한 번(NFR-2) — 스냅샷도 결과를 처음 넣은 트랜잭션에서만 넣는다
            if (inserted) record.members.forEach { insertMember(record, it) }
            // 마감 실패 그룹원(LLD platform §3) — Lua deadline 이 그룹 Hash 에서 바꾼 상태를 정본에도 남긴다. 조건부라 멱등.
            // 랭킹에서 FAILED 인 그룹원만 — 늦게 온 3번 풀이가 아직 반영 전인 그룹원을 PG 상태만 보고 FAILED 로 덮지 않는다(LLD v8)
            val failed = record.members.filter { it.status == MemberStatus.FAILED }.map { it.userId.value }
            if (record.result == GroupResultCode.FAILED_DEADLINE && failed.isNotEmpty()) {
                db.sql(
                    "UPDATE daily_progress SET status = 'FAILED', updated_at = now() " +
                        "WHERE game_date = :d AND group_id = :g AND user_id = ANY(:failed) " +
                        "AND status IN ('NOT_STARTED', 'IN_PROGRESS')",
                )
                    .bind("d", record.date).bind("g", record.groupId.value).bind("failed", failed.toTypedArray())
                    .fetch().awaitRowsUpdated()
            }
            inserted
        }

    private suspend fun insertMember(record: GroupDecidedRecord, member: ResultMemberRecord) {
        var spec = db.sql(
            "INSERT INTO group_result_members " +
                "(game_date, group_id, user_id, rank, progress, status, started, last_solved_at, pass_question_no, cheat_failed) " +
                "VALUES (:d, :g, :u, :rank, :p, :s, :started, :ls, :pass, :cheat) ON CONFLICT (game_date, user_id) DO NOTHING",
        )
            .bind("d", record.date).bind("g", record.groupId.value).bind("u", member.userId.value)
            .bind("rank", member.rank).bind("p", member.progress).bind("s", member.status.name)
            .bind("started", member.started).bind("cheat", member.cheatFailed)
        spec = member.lastSolvedAt?.let { spec.bind("ls", odt(it)) } ?: spec.bindNull("ls", OffsetDateTime::class.java)
        spec = member.passNo?.let { spec.bind("pass", it) } ?: spec.bindNull("pass", Int::class.javaObjectType)
        spec.fetch().awaitRowsUpdated()
    }

    /**
     * 문제 PK(hex) → `questions.id`. 없으면 예외 — 출제된 문제가 표에 없는 손상이라 기록을 버리지 않고 재처리한다
     * (버킷이 멈춰 `persister.stuck` 경보 — LLD solve-signal §2.3).
     */
    private suspend fun questionId(pk: String): Long =
        db.sql("SELECT id FROM questions WHERE pk_hash = :pk")
            .bind("pk", pk)
            .map { row -> row.get("id", Number::class.java)!!.toLong() }
            .awaitOneOrNull()
            ?: throw IllegalStateException("questions 에 없는 문제 PK: $pk")

    private fun odt(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
}
