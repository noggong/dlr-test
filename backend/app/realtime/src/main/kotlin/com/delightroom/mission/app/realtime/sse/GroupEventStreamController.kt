package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.core.service.RealtimeService
import com.delightroom.mission.core.service.StreamSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/** realtime-board — 그룹 현황 SSE 채널(FR-5 · §5.8). */
@RestController
@RequestMapping("/realtime/v1/games/today")
class GroupEventStreamController(
    private val realtimeService: RealtimeService,
    private val hub: GroupChannelHub,
    private val frames: SseFrames,
) {
    /**
     * 티켓을 한 번 쓰고 스트림을 연다(LLD realtime-board §4.3 · SPEC §5). [ticket] 이 없거나 모양이 틀려도 401 REALTIME_TICKET_INVALID 라
     * Spring 의 400 이 나지 않도록 `required = false` 로 받는다. `Last-Event-ID` 는 읽지 않는다(RB14).
     *
     * 차례: 티켓 `GETDEL` → 그룹 채널 구독(확인까지) → `board_read` → 첫 프레임 `board.snapshot`(RB5). 구독을 먼저 해야 읽기와 구독 사이의
     * 변경이 연결 버퍼에 남는다. 스트림을 열기 전의 거부(티켓 · 그룹 상태 없음)는 구독을 풀고 JSON 오류로 응답한다.
     */
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(
        @RequestParam(required = false) ticket: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Flow<ServerSentEvent<String>>> {
        val redeemed = realtimeService.redeemTicket(ticket)
        val connection = hub.attach(redeemed.groupId)
        // 본문을 수집하기 전에 요청이 끝나도 SseConnectionReleaseFilter 가 돌려준다
        exchange.attributes[SseConnectionReleaseFilter.ATTRIBUTE] = connection
        val snapshot = try {
            realtimeService.snapshot(redeemed)
        } catch (e: Throwable) {
            hub.detach(connection) // 취소 중에도 끝까지 한다
            throw e
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .header(ACCEL_BUFFERING, "no")
            .body(stream(connection, snapshot))
    }

    /** 스트림이 어떻게 끝나든(정상 종료 · 클라이언트 끊김 · 구독 끊김) 연결을 허브에 돌려준다. */
    private fun stream(connection: GroupChannelConnection, snapshot: StreamSnapshot): Flow<ServerSentEvent<String>> =
        flow {
            try {
                emit(frames.snapshot(snapshot))
                // 연결 때 이미 랭킹이 확정된 그룹은 snapshot 하나로 끝낸다(RB8)
                if (snapshot.rankingFinal) return@flow
                // TODO(T-RB-6): 버퍼의 메시지를 version 으로 걸러 이벤트 5종으로 보내고 · 15초 하트비트 · rankingFinal 뒤 종료.
                //  지금은 받아서 버린다 — 스트림은 끊길 때까지 열려 있다. 구독이 끊기면 GroupChannelLostException 으로 끝난다(I8 —
                //  응답이 이미 200 이라 서버 오류 로그가 남는다. 정상 종료로 바꿀지는 T-RB-6 에서 정한다).
                connection.messages.collect { }
            } finally {
                hub.detach(connection)
            }
        }

    private companion object {
        /** nginx 등 앞단 프록시가 프레임을 모아 두지 않게(SPEC §5). */
        const val ACCEL_BUFFERING = "X-Accel-Buffering"
    }
}
