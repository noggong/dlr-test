package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant
import java.time.LocalDate

/** 유저의 그날 문제은행 캐시 `bank:{<uid>}:<date>`(LLD game-session §2.1 · §2.3). 만들기는 언제나 「없을 때만」이다. */
interface QuestionBankCache {
    suspend fun get(userId: UserId, date: LocalDate): QuestionBank?

    /** `SET NX PXAT` [expireAt] — 새로 두었으면 true, 이미 있으면 false(있던 값을 그대로 둔다). */
    suspend fun putIfAbsent(userId: UserId, date: LocalDate, bank: QuestionBank, expireAt: Instant): Boolean

    /** [putIfAbsent] 를 여럿 한 번에(파이프라인 — `worker: prepare-questions`). 새로 둔 수를 돌려준다. */
    suspend fun putAllIfAbsent(date: LocalDate, banks: Map<UserId, QuestionBank>, expireAt: Instant): Int
}
