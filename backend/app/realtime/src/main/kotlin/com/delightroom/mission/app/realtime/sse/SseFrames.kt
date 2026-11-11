package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.app.realtime.dto.BoardSnapshotMessage
import com.delightroom.mission.app.realtime.dto.GroupPendingMessage
import com.delightroom.mission.app.realtime.dto.GroupRankingFinalMessage
import com.delightroom.mission.app.realtime.dto.GroupResultMessage
import com.delightroom.mission.app.realtime.dto.MemberEntry
import com.delightroom.mission.app.realtime.dto.MemberProgressedMessage
import com.delightroom.mission.app.realtime.dto.RealtimeEvent
import com.delightroom.mission.app.realtime.dto.RealtimeMessage
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.BoardEntry
import com.delightroom.mission.core.domain.board.BoardView
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.event.GroupChannelType
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.service.StreamSnapshot
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneId

/** 그룹 채널 메시지 하나로 만든 SSE 프레임. [last] 면 이 프레임 뒤 스트림을 끝낸다(`rankingFinal = true` — RB8). */
class StreamFrame(val event: ServerSentEvent<String>, val last: Boolean)

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
                sentAt = snapshot.at.inZone(zone),
            ),
        )
    }

    /**
     * 그룹 채널 메시지 → [viewer] 가 받을 프레임(LLD §4.4 표 · SPEC §6.2 ~ §6.5). 현황판은 메시지 `at` 기준 `BoardView.build` 라
     * 결과 확정 전 남의 `CHEAT_FAILED` 는 `IN_PROGRESS` 로 보인다(RB10). 표에 맞지 않는 메시지는 null — 경고 로그 `realtime.bad_message`.
     */
    fun event(message: GroupChannelMessage, viewer: UserId): StreamFrame? {
        val snapshot = message.snapshot
        val decided = snapshot.result != null
        val zone = properties.zone
        val board = BoardView.build(snapshot, viewer, message.at, properties.deadlineAt(message.date))
        val entries by lazy { board.entries.map { it.toMessage(zone) } }
        val status = board.groupStatus.name
        return when {
            message.type == GroupChannelType.GROUP_RANKING_FINAL -> {
                val decidedAt = snapshot.decidedAt
                if (!decided || !snapshot.rankingFinal || decidedAt == null) return bad(message, "랭킹 확정인데 결과 · rankingFinal · decidedAt 없음")
                StreamFrame(
                    frame(
                        RealtimeEvent.GROUP_RANKING_FINAL,
                        GroupRankingFinalMessage(message.version, message.date, status, decidedAt.inZone(zone), true, entries),
                    ),
                    last = true,
                )
            }
            decided -> {
                val decidedAt = snapshot.decidedAt ?: return bad(message, "결과가 있는데 decidedAt 없음")
                StreamFrame(
                    frame(
                        RealtimeEvent.GROUP_RESULT,
                        GroupResultMessage(message.version, message.date, status, decidedAt.inZone(zone), snapshot.rankingFinal, entries),
                    ),
                    last = snapshot.rankingFinal,
                )
            }
            message.type == GroupChannelType.GROUP_DECIDED -> bad(message, "결과 확정인데 hash.result 없음")
            message.type == GroupChannelType.GROUP_PENDING ->
                StreamFrame(
                    frame(RealtimeEvent.GROUP_PENDING, GroupPendingMessage(message.version, message.date, status, message.at.inZone(zone))),
                    last = false,
                )
            else -> {
                val changed = message.changedUserId?.let(snapshot.members::get) ?: return bad(message, "바뀐 그룹원이 Hash 에 없음")
                StreamFrame(
                    frame(
                        RealtimeEvent.MEMBER_PROGRESSED,
                        MemberProgressedMessage(
                            message.version, message.date, status, changed.loginId.value, entries, message.at.inZone(zone),
                        ),
                    ),
                    last = false,
                )
            }
        }
    }

    /** 하트비트 — 주석 줄 `: ping`(SPEC §6.6 · RB9). `id:` · `event:` 가 없다. */
    fun heartbeat(): ServerSentEvent<String> = HEARTBEAT

    private fun bad(message: GroupChannelMessage, reason: String): StreamFrame? {
        log.warn("realtime.bad_message gid={} type={} version={} reason={}", message.groupId.value, message.type.wire, message.version, reason)
        return null
    }

    private fun frame(event: String, message: RealtimeMessage): ServerSentEvent<String> =
        ServerSentEvent.builder(json.writeValueAsString(message)).id("${message.version}").event(event).build()

    private fun Instant.inZone(zone: ZoneId) = atZone(zone).toOffsetDateTime()

    private fun BoardEntry.toMessage(zone: ZoneId): MemberEntry =
        MemberEntry(
            userId = userId.value,
            rank = rank,
            progress = progress,
            status = status.name,
            started = started,
            lastSolvedAt = lastSolvedAt?.inZone(zone),
            passedAtQuestionNo = passedAtQuestionNo,
        )

    private companion object {
        val log = LoggerFactory.getLogger(SseFrames::class.java)
        val HEARTBEAT: ServerSentEvent<String> = ServerSentEvent.builder<String>().comment("ping").build()
    }
}
