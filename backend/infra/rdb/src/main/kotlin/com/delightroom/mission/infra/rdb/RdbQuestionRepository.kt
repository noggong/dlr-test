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

    override suspend fun markServed(userId: UserId, questionId: QuestionId): Unit = TODO("solve-signal")
}
