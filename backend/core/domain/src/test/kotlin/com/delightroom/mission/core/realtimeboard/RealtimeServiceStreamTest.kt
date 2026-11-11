package com.delightroom.mission.core.realtimeboard

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.board.BoardView
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupBoardRepository
import com.delightroom.mission.core.port.RealtimeTicketRepository
import com.delightroom.mission.core.service.RealtimeService
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** LLD realtime-board §4.3 1 · 2 · 4 · 5 — 티켓 교환(모양 · GETDEL)과 연결 때 현황판(모드 `stream` · 티켓의 날짜 · 보는 사람). */
class RealtimeServiceStreamTest {
    // 앱 시계는 다른 날이어도 snapshot 은 티켓의 날짜를 읽는다
    private val clock = MutableClock(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val gid = GroupId(7)
    private val luaNow = Instant.ofEpochSecond(0, BoardFixtures.micros(LocalTime.of(7, 30)) * 1_000)

    private class Boards(var answer: BoardRead) : GroupBoardRepository {
        val calls = mutableListOf<Triple<LocalDate, UserId, BoardReadMode>>()

        override suspend fun read(groupId: GroupId, date: LocalDate, userId: UserId, mode: BoardReadMode): BoardRead {
            calls += Triple(date, userId, mode)
            return answer
        }
    }

    private class Tickets(private val stored: Map<String, RealtimeTicket>) : RealtimeTicketRepository {
        val taken = mutableListOf<String>()

        override suspend fun saveNew(ticket: RealtimeTicket, ttl: Duration): Boolean = error("쓰지 않는다")

        override suspend fun take(value: String): RealtimeTicket? {
            taken += value
            return stored[value]
        }
    }

    private fun ticket(uid: Long) =
        RealtimeTicket(RealtimeTicket.newValue(SecureRandom()), UserId(uid), gid, BoardFixtures.DATE, luaNow.plusSeconds(30))

    private fun service(boards: Boards, tickets: Tickets = Tickets(emptyMap())) = RealtimeService(tickets, clock, boards, GameProperties())

    private fun invalid(block: suspend () -> Unit) =
        assertThatThrownBy { runBlocking { block() } }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(ErrorCode.REALTIME_TICKET_INVALID)
        }

    @Test
    fun `티켓이 없거나 모양이 틀리면 Redis 를 부르지 않고 REALTIME_TICKET_INVALID`() {
        val tickets = Tickets(emptyMap())
        val service = service(Boards(BoardRead.Opened(luaNow, emptyList())), tickets)
        for (value in listOf(null, "", "abc", "a".repeat(42), "a".repeat(44), "+".repeat(43))) invalid { service.redeemTicket(value) }
        assertThat(tickets.taken).isEmpty()
    }

    @Test
    fun `GETDEL 이 값을 주면 그 티켓, 없으면 REALTIME_TICKET_INVALID`() {
        val issued = ticket(BoardFixtures.MINJI)
        val tickets = Tickets(mapOf(issued.value to issued))
        val service = service(Boards(BoardRead.Opened(luaNow, emptyList())), tickets)
        assertThat(runBlocking { service.redeemTicket(issued.value) }).isEqualTo(issued)
        invalid { service.redeemTicket(RealtimeTicket.newValue(SecureRandom())) }
        assertThat(tickets.taken).hasSize(2)
    }

    @Test
    fun `snapshot 은 티켓의 날짜 · 주인으로 stream 모드를 읽고 조회와 같은 함수로 만든다`(): Unit = runBlocking {
        val hash = BoardFixtures.inProgressHash()
        val boards = Boards(BoardRead.Opened(luaNow, BoardFixtures.flat(hash)))
        val owner = ticket(BoardFixtures.MINJI)
        val snapshot = service(boards).snapshot(owner)

        assertThat(boards.calls).containsExactly(Triple(BoardFixtures.DATE, UserId(BoardFixtures.MINJI), BoardReadMode.STREAM))
        val expected = BoardView.build(
            GroupDaySnapshot.parse(BoardFixtures.DATE, hash), UserId(BoardFixtures.MINJI), luaNow,
            GameProperties().deadlineAt(BoardFixtures.DATE),
        )
        assertThat(snapshot.board).isEqualTo(expected)
        assertThat(snapshot.board.version).isEqualTo(BoardFixtures.VERSION)
        assertThat(snapshot.rankingFinal).isFalse()
        assertThat(snapshot.at).isEqualTo(luaNow)
    }

    @Test
    fun `rankingFinal 은 그룹 Hash 값을 그대로 싣는다`(): Unit = runBlocking {
        val hash = BoardFixtures.decidedHash() + ("rankingFinal" to "1")
        val snapshot = service(Boards(BoardRead.Opened(luaNow, BoardFixtures.flat(hash)))).snapshot(ticket(BoardFixtures.HANA))
        assertThat(snapshot.rankingFinal).isTrue()
    }

    @Test
    fun `board_read 가 거부하면 그 코드로 던진다`() {
        for (code in listOf(ErrorCode.GAME_NOT_OPEN, ErrorCode.NOT_PARTICIPANT, ErrorCode.SOLO_GROUP)) {
            assertThatThrownBy { runBlocking { service(Boards(BoardRead.Rejected(code))).snapshot(ticket(BoardFixtures.MINJI)) } }
                .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(code) }
        }
    }
}
