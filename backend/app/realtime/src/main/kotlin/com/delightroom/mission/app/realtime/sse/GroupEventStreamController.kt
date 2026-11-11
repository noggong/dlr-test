package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.service.RealtimeService
import com.delightroom.mission.core.service.StreamSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** realtime-board — 그룹 현황 SSE 채널(FR-5 · §5.8). */
@RestController
@RequestMapping("/realtime/v1/games/today")
class GroupEventStreamController(
    private val realtimeService: RealtimeService,
    private val hub: GroupChannelHub,
    private val frames: SseFrames,
    @param:Value("\${realtime.heartbeat:15s}") heartbeat: java.time.Duration,
) {
    private val heartbeat: Duration = heartbeat.toKotlinDuration()

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
            .body(stream(connection, snapshot, redeemed.userId))
    }

    /**
     * 스트림이 어떻게 끝나든(랭킹 확정 · 클라이언트 끊김 · 구독 끊김) 연결을 허브에 돌려준다(LLD §4.3 6 ~ 8).
     * snapshot 뒤 버퍼의 메시지를 차례로 받아 `version ≤ lastVersion` 은 버리고(RB5 · RB6) 나머지를 [viewer] 기준 프레임으로 보낸다.
     * 마지막 프레임 뒤 [heartbeat] 동안 아무것도 없으면 `: ping`(RB9). `rankingFinal = true` 프레임 직후 끝낸다(RB8).
     */
    private fun stream(connection: GroupChannelConnection, snapshot: StreamSnapshot, viewer: UserId): Flow<ServerSentEvent<String>> =
        flow {
            try {
                emit(frames.snapshot(snapshot))
                // 연결 때 이미 랭킹이 확정된 그룹은 snapshot 하나로 끝낸다(RB8)
                if (snapshot.rankingFinal) return@flow
                var lastVersion = snapshot.board.version
                while (true) {
                    val received = connection.receive(heartbeat)
                    if (received == null) {
                        emit(frames.heartbeat())
                        continue
                    }
                    val message = received.getOrNull()
                    if (message == null) {
                        // detach(원인 없음) 또는 구독 끊김 — 응답이 이미 200 이라 예외로 끝내지 않는다(LLD §4.3 8 c · I8)
                        val cause = received.exceptionOrNull()
                        if (cause is GroupChannelLostException) {
                            log.warn("realtime.channel_lost gid={} reason={}", connection.groupId.value, cause.message)
                        } else if (cause != null) {
                            throw cause
                        }
                        return@flow
                    }
                    if (message.version <= lastVersion) continue
                    val frame = frames.event(message, viewer) ?: continue
                    emit(frame.event)
                    lastVersion = message.version
                    if (frame.last) return@flow
                }
            } finally {
                hub.detach(connection)
            }
        }

    private companion object {
        val log = LoggerFactory.getLogger(GroupEventStreamController::class.java)

        /** nginx 등 앞단 프록시가 프레임을 모아 두지 않게(SPEC §5). */
        const val ACCEL_BUFFERING = "X-Accel-Buffering"
    }
}
