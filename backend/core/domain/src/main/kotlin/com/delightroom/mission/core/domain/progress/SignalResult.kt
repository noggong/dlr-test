package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import java.time.Instant

/** 신호 Lua 한 번의 답(LLD §3.1 · §3.5) — 받아들였거나([SignalResult]) 거부했다([SignalRejection]). */
sealed interface SignalReply {
    /** Lua 의 `t` — Redis 도착 시각(`TIME` + `game.clock-offset`). */
    val arrivedAt: Instant
}

/** `OK`(이번에 반영) · `REPLAY`(이미 반영 · 무시 — 같은 `200`). */
enum class SignalOutcome { APPLIED, REPLAY }

/** 받아들인 신호 — 응답 본문의 재료(SPEC SS13 · SS14). */
data class SignalResult(
    val outcome: SignalOutcome,
    /** -1 ~ 3 — 응답은 0 아래를 0 으로(SS13). */
    val progress: Int,
    /** 본인 기준 상태(부정 실패 본인은 CHEAT_FAILED). */
    val missionStatus: MemberStatus,
    val groupStatus: GroupStatus,
    val version: Long,
    override val arrivedAt: Instant,
    /** 패스를 쓴 번호 — `pass` 만 채운다(안 썼으면 null). */
    val passedAtQuestionNo: Int? = null,
) : SignalReply

/** 거부 — [code] 는 Lua `[1]`: [NO_ROSTER] 또는 같은 이름의 `ErrorCode`. */
data class SignalRejection(
    val code: String,
    override val arrivedAt: Instant,
) : SignalReply {
    companion object {
        /** 그날 명단이 아직 없다 — 서비스가 처리한다(LLD §3.4). */
        const val NO_ROSTER = "NO_ROSTER"
    }
}
