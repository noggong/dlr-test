package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupBoardRepository
import com.delightroom.mission.core.port.GroupEventSubscriber
import com.delightroom.mission.core.port.RealtimeTicketRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock

/** realtime-board — SSE 티켓 발급 · 교환과 그룹 이벤트 구독(FR-5 · §5.8). */
@Service
class RealtimeService(
    private val tickets: RealtimeTicketRepository,
    private val subscriber: GroupEventSubscriber,
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

    suspend fun redeemTicket(value: String): UserId =
        // TODO(realtime-board): 만료 · 1회 사용 검사
        throw NotImplementedYet("realtime-board")

    suspend fun groupEvents(userId: UserId): Flow<GroupChannelMessage> =
        // TODO(realtime-board): 유저의 그룹 채널 구독
        throw NotImplementedYet("realtime-board")

    private companion object {
        const val SAVE_ATTEMPTS = 2
        val RANDOM = SecureRandom()
    }
}
