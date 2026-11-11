package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RealtimeTicketRepository
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate

/**
 * `tk:{<ticket>}` = `{"uid","gid","date","exp"}`(모두 문자열 · LLD realtime-board §2.5). 발급은 `SET NX EX`, 사용은 `GETDEL` —
 * 꺼내기와 지우기가 한 명령이라 같은 티켓을 동시에 가져가도 한 쪽만 값을 받는다(SPEC RB4).
 */
internal class RedisRealtimeTicketRepository(
    private val redis: ReactiveStringRedisTemplate,
    private val keys: RedisKeys,
) : RealtimeTicketRepository {
    private val json: JsonMapper = JsonMapper.builder().build()

    override suspend fun saveNew(ticket: RealtimeTicket, ttl: Duration): Boolean {
        require(ttl.seconds > 0 && ttl.nano == 0) { "티켓 TTL 은 양의 초 단위다: $ttl" }
        val value = json.writeValueAsString(
            linkedMapOf(
                "uid" to ticket.userId.value.toString(),
                "gid" to ticket.groupId.value.toString(),
                "date" to ticket.date.toString(),
                "exp" to GameClock.epochMicros(ticket.expiresAt).toString(),
            ),
        )
        return redis.opsForValue().setIfAbsent(keys.ticket(ticket.value), value, ttl).awaitSingle()
    }

    override suspend fun take(value: String): RealtimeTicket? {
        val raw = redis.opsForValue().getAndDelete(keys.ticket(value)).awaitSingleOrNull() ?: return null
        val node = json.readTree(raw)
        fun field(name: String): String = checkNotNull(node.get(name)?.takeIf { it.isString }?.stringValue()) { "티켓 값에 $name 없음" }
        return RealtimeTicket(
            value = value,
            userId = UserId(field("uid").toLong()),
            groupId = GroupId(field("gid").toLong()),
            date = LocalDate.parse(field("date")),
            expiresAt = microsToInstant(field("exp").toLong()),
        )
    }
}
