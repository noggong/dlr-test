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
    /**
     * 티켓을 한 번 쓰고 스트림을 연다(realtime-board SPEC §5). [ticket] 이 없거나 모양이 틀리면 401 REALTIME_TICKET_INVALID 라
     * Spring 의 400 이 나지 않도록 `required = false` 로 받는다.
     */
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(@RequestParam(required = false) ticket: String?): Flow<ServerSentEvent<RealtimeMessage>> {
        // TODO(realtime-board): 티켓 교환(GETDEL) → board.snapshot → 그룹 이벤트 팬아웃 · 15초 하트비트 · rankingFinal 뒤 종료
        throw NotImplementedYet("realtime-board")
    }
}
