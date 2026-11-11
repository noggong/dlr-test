package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.app.realtime.dto.BoardSnapshotMessage
import com.delightroom.mission.app.realtime.dto.MemberEntry
import com.delightroom.mission.app.realtime.dto.RealtimeEvent
import com.delightroom.mission.app.realtime.dto.RealtimeMessage
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.BoardEntry
import com.delightroom.mission.core.service.StreamSnapshot
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId

/**
 * SSE 프레임 만들기(LLD realtime-board §4.3 · §4.4) — `id:` = `data.version`(RB15) · `event:` = 이벤트 이름 · `data:` = JSON 한 줄.
 * `data` 는 여기서 앱의 [JsonMapper](현황판 조회 응답과 같은 설정)로 문자열로 만든다 — 프레임 쓰기가 다시 인코딩하지 않으므로
 * 조회와 snapshot 의 `entries` 가 바이트까지 같다(RB12).
 */
@Component
class SseFrames(
    private val json: JsonMapper,
    private val properties: GameProperties,
) {
    /** 연결마다 첫 프레임 `board.snapshot`(SPEC §6.1). */
    fun snapshot(snapshot: StreamSnapshot): ServerSentEvent<String> {
        val board = snapshot.board
        val zone = properties.zone
        return frame(
            RealtimeEvent.BOARD_SNAPSHOT,
            BoardSnapshotMessage(
                version = board.version,
                date = board.date,
                groupStatus = board.groupStatus.name,
                entries = board.entries.map { it.toMessage(zone) },
                sentAt = snapshot.at.atZone(zone).toOffsetDateTime(),
            ),
        )
    }

    private fun frame(event: String, message: RealtimeMessage): ServerSentEvent<String> =
        ServerSentEvent.builder(json.writeValueAsString(message)).id("${message.version}").event(event).build()

    private fun BoardEntry.toMessage(zone: ZoneId): MemberEntry =
        MemberEntry(
            userId = userId.value,
            rank = rank,
            progress = progress,
            status = status.name,
            started = started,
            lastSolvedAt = lastSolvedAt?.atZone(zone)?.toOffsetDateTime(),
            passedAtQuestionNo = passedAtQuestionNo,
        )
}
