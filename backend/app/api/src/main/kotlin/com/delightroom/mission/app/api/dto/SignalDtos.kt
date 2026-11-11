package com.delightroom.mission.app.api.dto

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.progress.PassSignal
import com.delightroom.mission.core.domain.progress.SignalResult
import com.delightroom.mission.core.domain.progress.SolveSignal
import com.delightroom.mission.core.domain.question.QuestionId
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * `POST /api/v1/games/today/solves` — 풀이 신호(solve-signal SPEC §2).
 * 시각은 문자열로 받아 [toSignal] 에서 검사한다 — 오프셋이 없거나(SPEC §1) 숫자 타임스탬프면 400, `successTime` 은 받은 글자 그대로 기록한다.
 */
data class SolveRequest(
    /** 1 · 2 · 3 = 난이도 — JSON 정수만(`"1"` · `1.5` 거부). 타입을 직접 보려고 `Any?` 로 받는다. */
    val questionNo: Any?,
    /** 그 번호의 지금 문제(진입 신호로 고정된 문제) PK — JSON 문자열 `^[0-9a-f]{64}$`. */
    val questionId: Any?,
    /** 맞힌 시각(클라이언트, 오프셋 필수) — 기록만 한다(R-S5). */
    val successTime: String,
    /** 제출 클릭 시각(클라이언트, 오프셋 필수) — 08:00:00 ~ 08:00:03 도착분 승인에만(D1). */
    val clickedAt: String,
) {
    /** 본문 규칙(SPEC §2) — 어기면 `400 INVALID_REQUEST`. 서비스 · Redis 를 부르기 전에 검사한다(ORACLE O4). */
    fun toSignal(): SolveSignal {
        val success = offsetTime(successTime)
        return SolveSignal(questionNo(questionNo), questionId(questionId), success.toString(), offsetTime(clickedAt).toInstant())
    }
}

/** 풀이 `200` — FailureResponse 와 같은 형식. 상태는 enum 이름 문자열. */
data class SolveResponse(
    /** 0~3. */
    val progress: Int,
    /** MemberStatus — 본인 기준(SS13). */
    val missionStatus: String,
    /** GroupStatus(SS14). */
    val groupStatus: String,
) {
    companion object {
        fun of(result: SignalResult) =
            SolveResponse(result.progress.coerceAtLeast(0), result.missionStatus.name, result.groupStatus.name)
    }
}

/** `POST /api/v1/games/today/passes` — 패스 신호. */
data class PassRequest(
    /** JSON 정수 1 · 2 · 3. */
    val questionNo: Any?,
    /** JSON 문자열 `^[0-9a-f]{64}$`. */
    val questionId: Any?,
) {
    fun toSignal(): PassSignal = PassSignal(questionNo(questionNo), questionId(questionId))
}

data class PassResponse(
    /** 오늘 패스를 쓴 번호(서버 저장 값). */
    val passedAtQuestionNo: Int,
    /** 교체 문제로 진입 신호를 보낼 번호 — 패스한 번호와 같다(요청 값). */
    val questionNo: Int,
    /** 지금 진행도(패스는 바꾸지 않는다). */
    val progress: Int,
) {
    companion object {
        fun of(result: SignalResult, questionNo: Int) =
            PassResponse(checkNotNull(result.passedAtQuestionNo), questionNo, result.progress.coerceAtLeast(0))
    }
}

/** `POST /api/v1/games/today/failures` `200` — 부정 실패(R-F2). SolveResponse 와 같은 형식. */
data class FailureResponse(
    val progress: Int,
    val missionStatus: String,
    val groupStatus: String,
) {
    companion object {
        fun of(result: SignalResult) =
            FailureResponse(result.progress.coerceAtLeast(0), result.missionStatus.name, result.groupStatus.name)
    }
}

private val QUESTION_ID = Regex("^[0-9a-f]{64}$")

private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_REQUEST)

/**
 * 시각 칸이 받는 범위 [2000-01-01, 2100-01-01) KST — µs 변환 넘침과 PostgreSQL `TIMESTAMPTZ` 범위 밖 기록을 막는다(LLD §4).
 * 서버의 날짜 · 시각 판단은 모두 KST(api-guide §1 · T-PF-15)라 경계도 `+09:00` 으로 적는다 — 보낸 오프셋과 상관없이 같은 시점이면 같은 판정.
 */
private val EARLIEST: Instant = OffsetDateTime.parse("2000-01-01T00:00:00+09:00").toInstant()
private val LATEST: Instant = OffsetDateTime.parse("2100-01-01T00:00:00+09:00").toInstant()

/** JSON 정수 1~3 — Jackson 이 `Int`(작은 정수)로 준 것만. 문자열 · 실수 · 불리언은 거부(형변환 없음). 진입 신호도 쓴다. */
internal fun questionNo(no: Any?): Int = if (no is Int && no in 1..3) no else invalid()

/** JSON 문자열 `^[0-9a-f]{64}$` — 진입 신호도 쓴다. */
internal fun questionId(id: Any?): QuestionId = if (id is String && QUESTION_ID.matches(id)) QuestionId(id) else invalid()

/** ISO-8601 · 오프셋 필수(api-guide §1) — `2026-11-12T07:00:00` 처럼 오프셋이 없거나 KST 기준 2000 ~ 2099 년 밖이면 거부한다. */
private fun offsetTime(value: String): OffsetDateTime {
    val parsed = try {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (e: DateTimeParseException) {
        invalid()
    }
    val instant = parsed.toInstant()
    return if (instant >= EARLIEST && instant < LATEST) parsed else invalid()
}
