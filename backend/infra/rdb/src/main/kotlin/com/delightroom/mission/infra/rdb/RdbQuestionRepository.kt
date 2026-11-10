package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.QuestionRepository

internal class RdbQuestionRepository : QuestionRepository {
    override suspend fun findBankFor(userId: UserId): QuestionBank = TODO("game-session")

    override suspend fun markServed(userId: UserId, questionId: QuestionId): Unit = TODO("solve-signal")
}
