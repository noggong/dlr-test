package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.question.Category
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.Question
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.QuestionRepository
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow

/** questions · served_questions (LLD game-session §6). 쿼리 id 는 LLD 표와 같다. */
internal class RdbQuestionRepository(
    private val db: DatabaseClient,
) : QuestionRepository {
    /** Q1 — `pk_hash` · `answer_hash` 는 CHAR(64) 라 그대로 64자 hex 다. */
    override suspend fun loadAll(): List<Question> =
        db.sql("SELECT id, pk_hash, difficulty, category, body, answer_hash FROM questions ORDER BY id")
            .map { row ->
                Question(
                    internalId = row.get("id", Number::class.java)!!.toLong(),
                    id = QuestionId(row.get("pk_hash", String::class.java)!!),
                    difficulty = Difficulty.of(row.get("difficulty", Number::class.java)!!.toInt()),
                    category = Category.valueOf(row.get("category", String::class.java)!!),
                    body = row.get("body", String::class.java)!!,
                    answerHash = row.get("answer_hash", String::class.java)!!,
                )
            }
            .flow()
            .toList()

    /** Q1 의 worker 판 — 문제 수만큼의 Long 만 올린다. */
    override suspend fun loadIdsByDifficulty(): Map<Difficulty, LongArray> {
        val rows = db.sql("SELECT id, difficulty FROM questions ORDER BY id")
            .map { row ->
                Difficulty.of(row.get("difficulty", Number::class.java)!!.toInt()) to
                    row.get("id", Number::class.java)!!.toLong()
            }
            .flow()
            .toList()
        val grouped = rows.groupBy({ it.first }, { it.second })
        return Difficulty.entries.associateWith { grouped[it].orEmpty().toLongArray() }
    }

    /** Q3 — PK 앞부분 `user_id` 로 인덱스 범위 조회. */
    override suspend fun findServedIds(userIds: Collection<UserId>): Map<UserId, Set<Long>> {
        if (userIds.isEmpty()) return emptyMap()
        val result = HashMap<UserId, MutableSet<Long>>()
        db.sql("SELECT user_id, question_id FROM served_questions WHERE user_id = ANY(:ids)")
            .bind("ids", userIds.map { it.value }.toTypedArray())
            .map { row ->
                row.get("user_id", Number::class.java)!!.toLong() to row.get("question_id", Number::class.java)!!.toLong()
            }
            .flow()
            .collect { (uid, qid) -> result.getOrPut(UserId(uid)) { HashSet() }.add(qid) }
        return result
    }

    override suspend fun markServed(userId: UserId, questionId: QuestionId): Unit = TODO("solve-signal")
}
