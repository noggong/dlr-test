package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupEventSubscriber
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.port.UserRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import java.time.Clock

/** realtime-board — SSE 티켓 발급 · 교환과 그룹 이벤트 구독(FR-5 · §5.8). */
@Service
class RealtimeService(
    private val tickets: RealtimeTicketRepository,
    private val users: UserRepository,
    private val subscriber: GroupEventSubscriber,
    private val clock: Clock,
) {
    suspend fun issueTicket(userId: UserId): RealtimeTicket =
        // TODO(realtime-board): 단기 1회용 티켓
        throw NotImplementedYet("realtime-board")

    suspend fun redeemTicket(value: String): UserId =
        // TODO(realtime-board): 만료 · 1회 사용 검사
        throw NotImplementedYet("realtime-board")

    suspend fun groupEvents(userId: UserId): Flow<GroupChannelMessage> =
        // TODO(realtime-board): 유저의 그룹 채널 구독
        throw NotImplementedYet("realtime-board")
}
