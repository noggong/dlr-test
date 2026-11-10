package com.delightroom.mission.app.api.dto

import java.time.OffsetDateTime

/** `POST /api/v1/games/today/solves` — 풀이 신호(solve-signal SPEC §2). */
data class SolveRequest(
    /** 1 · 2 · 3 = 난이도. */
    val questionNo: Int,
    /** 그 번호의 지금 문제(진입 신호로 고정된 문제) PK. */
    val questionId: String,
    /** 맞힌 시각(클라이언트, 오프셋 필수) — 기록만 한다(R-S5). */
    val successTime: OffsetDateTime,
    /** 제출 클릭 시각(클라이언트, 오프셋 필수) — 08:00:00 ~ 08:00:03 도착분 승인에만(D1). */
    val clickedAt: OffsetDateTime,
)

/** 풀이 `200` — FailureResponse 와 같은 형식. 상태는 enum 이름 문자열. */
data class SolveResponse(
    /** 0~3. */
    val progress: Int,
    /** MemberStatus — 본인 기준(SS13). */
    val missionStatus: String,
    /** GroupStatus(SS14). */
    val groupStatus: String,
)

/** `POST /api/v1/games/today/passes` — 패스 신호. */
data class PassRequest(
    val questionNo: Int,
    val questionId: String,
)

data class PassResponse(
    /** 오늘 패스를 쓴 번호(서버 저장 값). */
    val passedAtQuestionNo: Int,
    /** 교체 문제로 진입 신호를 보낼 번호 — 패스한 번호와 같다. */
    val questionNo: Int,
    /** 지금 진행도(패스는 바꾸지 않는다). */
    val progress: Int,
)

/** `POST /api/v1/games/today/failures` `200` — 부정 실패(R-F2). SolveResponse 와 같은 형식. */
data class FailureResponse(
    val progress: Int,
    val missionStatus: String,
    val groupStatus: String,
)
