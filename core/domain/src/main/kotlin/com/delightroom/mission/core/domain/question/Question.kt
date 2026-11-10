package com.delightroom.mission.core.domain.question

import com.delightroom.mission.common.error.NotImplementedYet

/** 문제 고유 PK(hash) (R-B2). */
@JvmInline
value class QuestionId(val value: String)

/** 난이도 1 · 2 · 3 — 문제 번호와 같다(R-B5). */
enum class Difficulty(val level: Int) {
    ONE(1),
    TWO(2),
    THREE(3),
}

/** 서버 보관용. 실제 정답과 정답 해시를 따로 둔다(R-A3). 클라이언트에는 [answerHash] 만 나간다. */
class Question(
    val id: QuestionId,
    val difficulty: Difficulty,
    val answer: String,
    val answerHash: String,
)

/** 한 유저에게 내려줄 문제은행 — 이미 낸 문제는 뺀다(R-B3). 크기는 Q9. */
class QuestionBank(
    val questions: List<Question>,
) {
    fun forDifficulty(difficulty: Difficulty): List<Question> = throw NotImplementedYet("game-session")
}
