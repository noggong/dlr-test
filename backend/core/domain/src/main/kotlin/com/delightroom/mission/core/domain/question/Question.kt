package com.delightroom.mission.core.domain.question

/** 문제 고유 PK(hash) (R-B2). */
@JvmInline
value class QuestionId(val value: String)

/** 난이도 1 · 2 · 3 — 문제 번호와 같다(R-B5). */
enum class Difficulty(val level: Int) {
    ONE(1),
    TWO(2),
    THREE(3),
    ;

    companion object {
        fun of(level: Int): Difficulty =
            entries.firstOrNull { it.level == level } ?: throw IllegalArgumentException("난이도는 1 · 2 · 3 이다: $level")
    }
}

/** 분야(`questions.category`). */
enum class Category {
    CURRENT,
    POLITICS,
    SOCIETY,
    GENERAL,
}

/**
 * 문제 캐시에 두는 문제(LLD game-session §1 · Q1). 실제 정답은 두지 않는다 — 클라이언트에는 [answerHash] 만 나간다(R-A3).
 *
 * @property internalId `questions.id` — 은행 · 출제 이력 · 이벤트 `qid` 가 쓰는 내부 ID. 클라이언트에 내보내지 않는다.
 */
class Question(
    val internalId: Long,
    val id: QuestionId,
    val difficulty: Difficulty,
    val category: Category,
    val body: String,
    val answerHash: String,
)

/**
 * 한 유저의 그날 문제은행 — 난이도마다 내부 ID 목록, 목록 순서 = 응답 순서(LLD §2.3).
 * 이미 낸 문제는 뺀 것이고(R-B3) 난이도마다 1 ~ 20개다(prd D10).
 */
class QuestionBank(
    val idsByDifficulty: Map<Difficulty, List<Long>>,
) {
    init {
        require(Difficulty.entries.all { !idsByDifficulty[it].isNullOrEmpty() }) { "은행은 난이도마다 1개 이상이다" }
    }

    fun forDifficulty(difficulty: Difficulty): List<Long> = idsByDifficulty.getValue(difficulty)

    override fun equals(other: Any?): Boolean = other is QuestionBank && other.idsByDifficulty == idsByDifficulty

    override fun hashCode(): Int = idsByDifficulty.hashCode()

    override fun toString(): String = "QuestionBank($idsByDifficulty)"
}
