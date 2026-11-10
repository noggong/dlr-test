package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.app.realtime.dto.RealtimeMessage
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.BoardService
import com.delightroom.mission.core.service.RealtimeService
import kotlinx.coroutines.flow.Flow
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** realtime-board — 그룹 현황 SSE 채널(FR-5 · §5.8). */
@RestController
@RequestMapping("/realtime/v1/games/today")
class GroupEventStreamController(
    private val realtimeService: RealtimeService,
    private val boardService: BoardService,
    private val connections: SseConnectionRegistry,
) {
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(@RequestParam ticket: String): Flow<ServerSentEvent<RealtimeMessage>> {
        // TODO(realtime-board): 티켓 교환 → board.snapshot → 그룹 이벤트 팬아웃 · 하트비트
        throw NotImplementedYet("realtime-board")
    }
}
