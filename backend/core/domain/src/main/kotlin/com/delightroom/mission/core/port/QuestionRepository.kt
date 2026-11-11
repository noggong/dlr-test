package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.Question
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId

/** 문제 원천과 유저별 출제 기록(평생 중복 금지 R-B7). */
interface QuestionRepository {
    /** `questions` 전체 — 문제 캐시 적재(LLD game-session Q1). 실제 정답은 읽지 않는다. */
    suspend fun loadAll(): List<Question>

    /** 난이도마다 문제 내부 ID 오름차순 — Q1 의 worker 판(`id` · `difficulty` 만). 문제가 없는 난이도는 빈 배열. */
    suspend fun loadIdsByDifficulty(): Map<Difficulty, LongArray>

    /** 여러 유저의 출제 이력(`served_questions.question_id`) — LLD Q3. 이력이 없는 유저는 결과에 없다. */
    suspend fun findServedIds(userIds: Collection<UserId>): Map<UserId, Set<Long>>

    /** 맞힌 문제 · 패스한 문제를 이 유저의 출제 기록에 남긴다(R-S2 · R-P3). */
    suspend fun markServed(userId: UserId, questionId: QuestionId)
}
