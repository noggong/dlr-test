package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.board.Board
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.board.BoardView
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupBoardRepository
import com.delightroom.mission.core.port.RealtimeTicketRepository
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

/** SSE 첫 프레임 `board.snapshot` 의 재료(LLD realtime-board §4.3 5). */
class StreamSnapshot(
    /** 티켓 주인 기준 현황판. */
    val board: Board,
    /** 그룹 Hash `rankingFinal = 1` — 참이면 snapshot 하나 뒤 스트림을 닫는다(RB8). */
    val rankingFinal: Boolean,
    /** 판정한 Redis 시각(`TIME + offset`) — `sentAt`. */
    val at: Instant,
)

/**
 * realtime-board — SSE 티켓 발급 · 교환과 연결 때 현황판(FR-5 · §5.8). 그룹 채널 구독은 노드 안에서 나눠 써야 하므로
 * 실시간 앱의 `GroupChannelHub` 가 하고, 구독 → [snapshot] 차례도 그 앱이 지킨다(RB5).
 */
@Service
class RealtimeService(
    private val tickets: RealtimeTicketRepository,
    private val clock: Clock,
    private val boards: GroupBoardRepository,
    private val properties: GameProperties,
) {
    /**
     * SSE 티켓 발급(LLD realtime-board §4.2 · SPEC §4). 창(07:00:00 ~ 08:00:03) · 명단 · 그룹원 · 1명 그룹 판정은 Lua `board_read`
     * (모드 `ticket`)가 하고, 거부는 그 코드의 [BusinessException] — 그때는 티켓을 만들지 않는다. 만료 = Lua 시각 + 30초.
     * 값이 겹쳐 `SET NX` 가 실패하면 한 번만 새로 만든다 — 32바이트 난수라 두 번 겹치면 난수원 결함이므로 500 으로 둔다.
     */
    suspend fun issueTicket(userId: UserId, groupId: GroupId): RealtimeTicket {
        val date = properties.now(clock).atZone(properties.zone).toLocalDate()
        val now = when (val read = boards.read(groupId, date, userId, BoardReadMode.TICKET)) {
            is BoardRead.Rejected -> throw BusinessException(read.code)
            is BoardRead.Opened -> read.now
        }
        repeat(SAVE_ATTEMPTS) {
            val ticket = RealtimeTicket(RealtimeTicket.newValue(RANDOM), userId, groupId, date, now.plus(RealtimeTicket.TTL))
            if (tickets.saveNew(ticket, RealtimeTicket.TTL)) return ticket
        }
        error("티켓 값이 $SAVE_ATTEMPTS 번 겹쳤다")
    }

    /**
     * SSE 티켓을 한 번 쓴다(LLD realtime-board §4.3 1 · 2 · RB4). 없거나 모양이 틀리면 Redis 를 부르지 않고, 없는 티켓 · 만료 · 이미 쓴 티켓은
     * `GETDEL` 이 null 을 준다 — 모두 `REALTIME_TICKET_INVALID`. 만료는 Redis TTL 이 정한다.
     */
    suspend fun redeemTicket(value: String?): RealtimeTicket {
        if (value == null || !RealtimeTicket.isWellFormed(value)) throw BusinessException(ErrorCode.REALTIME_TICKET_INVALID)
        return tickets.take(value) ?: throw BusinessException(ErrorCode.REALTIME_TICKET_INVALID)
    }

    /**
     * 연결 때 현황판(LLD §4.3 4 · 5) — `board_read`(모드 `stream`, 창 없음)로 티켓의 날짜 · 그룹을 읽어 티켓 주인 기준으로 만든다.
     * 조회(`BoardService.board`)와 같은 [BoardView.build] 라 같은 Hash 면 같은 `entries` 다(RB12). 거부는 그 코드의 [BusinessException].
     * 그룹 채널을 **구독한 뒤에** 부른다 — 그 사이 변경은 구독 버퍼가 받는다(RB5).
     */
    suspend fun snapshot(ticket: RealtimeTicket): StreamSnapshot =
        when (val read = boards.read(ticket.groupId, ticket.date, ticket.userId, BoardReadMode.STREAM)) {
            is BoardRead.Rejected -> throw BusinessException(read.code)
            is BoardRead.Opened -> {
                val snapshot = GroupDaySnapshot.parseFlat(ticket.date, read.fields)
                val board = BoardView.build(snapshot, ticket.userId, read.now, properties.deadlineAt(ticket.date))
                StreamSnapshot(board, snapshot.rankingFinal, read.now)
            }
        }

    private companion object {
        const val SAVE_ATTEMPTS = 2
        val RANDOM = SecureRandom()
    }
}
