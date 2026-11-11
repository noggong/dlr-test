package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.board.MemberStatus

/** 진입 신호 판정(Lua `shown` — LLD game-session §4.3) 결과. */
sealed interface ShownOutcome {
    /**
     * 그 번호의 지금 문제가 요청한 문제다 — [created] 는 이번 호출이 고정했는지(재접속 · 재시도는 false, 이벤트 없음 — GS10).
     * [progress] 는 내부 진행도(= `questionNo − 1`, 0~2), [status] 는 언제나 `IN_PROGRESS`.
     */
    data class Shown(val progress: Int, val status: MemberStatus, val created: Boolean) : ShownOutcome

    /** 그날 명단이 아직 없다 — 명단을 만들고 한 번 다시 부른다(§3.3-3). */
    data object NoRoster : ShownOutcome

    /** 판정에서 거부(GS9) — 시간 창 · 그룹원 · 1명 그룹 · 시작 전 · 완료 · `QUESTION_MISMATCH` · `QUESTION_OUT_OF_ORDER`. */
    data class Rejected(val code: ErrorCode) : ShownOutcome
}
