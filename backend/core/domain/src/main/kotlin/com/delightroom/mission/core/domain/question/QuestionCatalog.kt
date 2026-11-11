package com.delightroom.mission.core.domain.question

/**
 * 문제 캐시 — `api` · `worker` 가 기동 때 `questions` 전체(LLD Q1)를 한 번 올려 둔다(LLD game-session §2.3).
 * 은행 뽑기는 [idsByDifficulty] 만, 응답 · 진입 신호는 [byId] · [byPk] 를 쓴다.
 */
class QuestionCatalog(questions: List<Question>) {
    val byId: Map<Long, Question> = questions.associateBy { it.internalId }
    val byPk: Map<QuestionId, Question> = questions.associateBy { it.id }

    /** 난이도마다 내부 ID 오름차순. 문제가 없는 난이도도 빈 배열로 둔다. */
    val idsByDifficulty: Map<Difficulty, LongArray> =
        Difficulty.entries.associateWith { d ->
            questions.filter { it.difficulty == d }.map { it.internalId }.sorted().toLongArray()
        }

    init {
        require(byId.size == questions.size) { "문제 캐시에 같은 내부 ID 가 둘 있다" }
        require(byPk.size == questions.size) { "문제 캐시에 같은 PK 가 둘 있다" }
    }

    val size: Int get() = byId.size
}
