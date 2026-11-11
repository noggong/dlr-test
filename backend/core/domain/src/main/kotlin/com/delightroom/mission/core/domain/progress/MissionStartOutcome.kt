package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.common.error.ErrorCode
import java.time.Instant

/** 미션 시작 판정(Lua `start` — LLD game-session §4.2) 결과. */
sealed interface MissionStartOutcome {
    /**
     * 시작했거나 이미 시작해 있다 — [startedAt] 은 그날 처음 시작한 시각(Redis `TIME` + 오프셋, µs)이고 다시 불러도 같다(GS5).
     * [created] 는 이번 호출이 시작시켰는지(재접속 · 재시도 · 동시 요청의 두 번째는 false).
     */
    data class Started(val startedAt: Instant, val created: Boolean) : MissionStartOutcome

    /** 그날 명단이 아직 없다 — 명단을 만들고 한 번 다시 부른다(§3.2-2). */
    data object NoRoster : MissionStartOutcome

    /** 시간 창 · 그룹원 · 1명 그룹 판정에서 거부(GS14) — `GAME_NOT_OPEN` · `SUBMISSION_CLOSED` · `NOT_PARTICIPANT` · `SOLO_GROUP`. */
    data class Rejected(val code: ErrorCode) : MissionStartOutcome
}
