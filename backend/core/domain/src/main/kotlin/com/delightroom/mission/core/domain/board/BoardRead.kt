package com.delightroom.mission.core.domain.board

import com.delightroom.mission.common.error.ErrorCode
import java.time.Instant

/** 현황판 읽기의 쓰임 — 시간 창이 다르다(LLD realtime-board §3). */
enum class BoardReadMode {
    /** 현황판 조회 — 07:00:00 ~ 08:00:00. */
    BOARD,

    /** 티켓 발급 — 07:00:00 ~ 08:00:03, 그룹 Hash 를 읽지 않는다. */
    TICKET,

    /** SSE 연결 — 창 없음(티켓을 낼 때 이미 봤다). */
    STREAM,
}

/** Lua `board_read` 한 번의 결과. */
sealed interface BoardRead {
    /** 판정에 걸림 — [code] 는 `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP`. */
    data class Rejected(val code: ErrorCode) : BoardRead

    /**
     * 통과 — [now] 는 판정한 Redis 시각(`TIME + offset`), [fields] 는 그룹 Hash `HGETALL` 평탄 목록
     * ([GroupDaySnapshot.parseFlat] 에 그대로). [BoardReadMode.TICKET] 이면 비어 있다.
     */
    data class Opened(val now: Instant, val fields: List<String>) : BoardRead
}
