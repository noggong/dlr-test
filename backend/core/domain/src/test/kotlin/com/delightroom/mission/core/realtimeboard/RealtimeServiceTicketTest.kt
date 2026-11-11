package com.delightroom.mission.core.realtimeboard

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
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
import com.delightroom.mission.core.service.RealtimeService
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** LLD realtime-board §4.2 — 판정은 `board_read`(모드 `ticket`), 만료 = Lua 시각 + 30초, `SET NX` 충돌은 한 번만 다시. */
class RealtimeServiceTicketTest {
    private val luaNow = Instant.parse("2026-11-11T22:30:12.481123Z") // 2026-11-12 07:30 KST
    private val clock = MutableClock(Instant.parse("2026-11-11T22:30:00Z"), java.time.ZoneOffset.UTC)
    private val uid = UserId(11)
    private val gid = GroupId(7)

    private class Boards(var answer: BoardRead) : GroupBoardRepository {
        val calls = mutableListOf<Pair<LocalDate, BoardReadMode>>()

        override suspend fun read(groupId: GroupId, date: LocalDate, userId: UserId, mode: BoardReadMode): BoardRead {
            calls += date to mode
            return answer
        }
    }

    private class Tickets(private val accept: List<Boolean>) : RealtimeTicketRepository {
        val saved = mutableListOf<Pair<RealtimeTicket, Duration>>()

        override suspend fun saveNew(ticket: RealtimeTicket, ttl: Duration): Boolean {
            saved += ticket to ttl
            return accept[saved.size - 1]
        }

        override suspend fun take(value: String): RealtimeTicket? = error("쓰지 않는다")
    }

    private object NoSubscriber : GroupEventSubscriber {
        override fun subscribe(groupId: GroupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage> = error("쓰지 않는다")
    }

    private fun service(boards: Boards, tickets: Tickets) =
        RealtimeService(tickets, NoSubscriber, clock, boards, GameProperties())

    @Test
    fun `통과하면 티켓 모드로 읽은 날짜 · Lua 시각 + 30초로 저장하고 돌려준다`(): Unit = runBlocking {
        val boards = Boards(BoardRead.Opened(luaNow, emptyList()))
        val tickets = Tickets(listOf(true))
        val ticket = service(boards, tickets).issueTicket(uid, gid)

        assertThat(boards.calls).containsExactly(LocalDate.of(2026, 11, 12) to BoardReadMode.TICKET)
        assertThat(ticket.value).matches("^[A-Za-z0-9_-]{43}$")
        assertThat(ticket.userId).isEqualTo(uid)
        assertThat(ticket.groupId).isEqualTo(gid)
        assertThat(ticket.date).isEqualTo(LocalDate.of(2026, 11, 12))
        assertThat(ticket.expiresAt).isEqualTo(luaNow.plusSeconds(30))
        assertThat(tickets.saved).containsExactly(ticket to Duration.ofSeconds(30))
    }

    @Test
    fun `거부되면 그 코드로 던지고 저장하지 않는다`() {
        for (code in listOf(ErrorCode.GAME_NOT_OPEN, ErrorCode.NOT_PARTICIPANT, ErrorCode.SOLO_GROUP)) {
            val tickets = Tickets(emptyList())
            assertThatThrownBy { runBlocking { service(Boards(BoardRead.Rejected(code)), tickets).issueTicket(uid, gid) } }
                .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(code) }
            assertThat(tickets.saved).isEmpty()
        }
    }

    @Test
    fun `값이 한 번 겹치면 새 값으로 다시 저장한다`(): Unit = runBlocking {
        val tickets = Tickets(listOf(false, true))
        val ticket = service(Boards(BoardRead.Opened(luaNow, emptyList())), tickets).issueTicket(uid, gid)
        assertThat(tickets.saved).hasSize(2)
        assertThat(tickets.saved[0].first.value).isNotEqualTo(tickets.saved[1].first.value)
        assertThat(ticket).isEqualTo(tickets.saved[1].first)
    }

    @Test
    fun `두 번 겹치면 IllegalStateException(500)`() {
        val tickets = Tickets(listOf(false, false))
        assertThatThrownBy { runBlocking { service(Boards(BoardRead.Opened(luaNow, emptyList())), tickets).issueTicket(uid, gid) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(tickets.saved).hasSize(2)
    }
}
