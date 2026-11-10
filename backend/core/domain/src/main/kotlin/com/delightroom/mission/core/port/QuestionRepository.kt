package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId

/** 문제은행 원천과 유저별 출제 기록(평생 중복 금지 R-B7). */
interface QuestionRepository {
    /** 이 유저에게 아직 내지 않은 문제(R-B3). 크기는 Q9. */
    suspend fun findBankFor(userId: UserId): QuestionBank

    /** 맞힌 문제 · 패스한 문제를 이 유저의 출제 기록에 남긴다(R-S2 · R-P3). */
    suspend fun markServed(userId: UserId, questionId: QuestionId)
}
