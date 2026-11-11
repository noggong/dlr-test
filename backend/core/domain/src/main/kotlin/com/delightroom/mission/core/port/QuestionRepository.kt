package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.question.Question

/** 문제 원천과 유저별 출제 기록(평생 중복 금지 R-B7). 맞힌 · 패스한 문제의 기록은 `worker: persister` 가 한다(LLD solve-signal §2.3). */
interface QuestionRepository {
    /** `questions` 전체 — 문제 캐시 적재(LLD game-session Q1). 실제 정답은 읽지 않는다. */
    suspend fun loadAll(): List<Question>
}
