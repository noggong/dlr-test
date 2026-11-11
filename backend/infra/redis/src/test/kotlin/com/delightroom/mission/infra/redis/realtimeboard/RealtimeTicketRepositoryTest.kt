package com.delightroom.mission.infra.redis.realtimeboard

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.RealtimeTicket
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisRealtimeTicketRepository
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * ORACLE realtime-board I1 · O8(저장 쪽) — 티켓 `SET NX EX` · `GETDEL`(LLD §2.5). 티켓 1장을 100개가 동시에 가져가도 1개만 받는다.
 */
class RealtimeTicketRepositoryTest {
    private val redis = RedisTestContainer.template
    private val keys = RedisKeys(GameProperties())
    private val repository = RedisRealtimeTicketRepository(redis, keys)
    private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    private val created = mutableListOf<String>()

    @AfterEach
    fun cleanUp(): Unit = runBlocking {
        if (created.isNotEmpty()) redis.delete(*created.map(keys::ticket).toTypedArray()).awaitSingle()
        Unit
    }

    private fun newTicket(): RealtimeTicket =
        RealtimeTicket(
            value = RealtimeTicket.newValue(SecureRandom()),
            userId = UserId(MINJI),
            groupId = gid,
            date = DATE,
            expiresAt = Instant.parse("2026-11-11T22:30:12.481123Z"),
        ).also { created += it.value }

    @Test
    fun `O8 saveNew 는 tk 키에 uid · gid · date · exp 문자열 JSON 을 TTL 30 으로 쓴다`(): Unit = runBlocking {
        val ticket = newTicket()
        assertThat(repository.saveNew(ticket, RealtimeTicket.TTL)).isTrue()
        val key = "tk:{${ticket.value}}"
        val ttl = redis.getExpire(key).awaitSingle()
        assertThat(ttl.seconds).isBetween(29L, 30L)
        val stored: Map<String, Any?> = jacksonObjectMapper().readValue(redis.opsForValue().get(key).awaitSingle())
        assertThat(stored).isEqualTo(
            mapOf("uid" to "$MINJI", "gid" to "${gid.value}", "date" to "$DATE", "exp" to "1794436212481123"),
        )
    }

    @Test
    fun `같은 값이 이미 있으면 saveNew 는 false 이고 원래 값을 바꾸지 않는다`(): Unit = runBlocking {
        val ticket = newTicket()
        assertThat(repository.saveNew(ticket, RealtimeTicket.TTL)).isTrue()
        val other = ticket.copy(userId = UserId(999L))
        assertThat(repository.saveNew(other, RealtimeTicket.TTL)).isFalse()
        assertThat(repository.take(ticket.value)).isEqualTo(ticket)
    }

    @Test
    fun `take 는 저장한 티켓을 돌려주고 지운다 — 두 번째는 null`(): Unit = runBlocking {
        val ticket = newTicket()
        repository.saveNew(ticket, RealtimeTicket.TTL)
        assertThat(repository.take(ticket.value)).isEqualTo(ticket)
        assertThat(redis.opsForValue().get(keys.ticket(ticket.value)).awaitSingleOrNull()).isNull()
        assertThat(repository.take(ticket.value)).isNull()
    }

    @Test
    fun `없는 티켓 · TTL 이 지난 티켓은 null`(): Unit = runBlocking {
        assertThat(repository.take(RealtimeTicket.newValue(SecureRandom()))).isNull()
        val ticket = newTicket()
        repository.saveNew(ticket, Duration.ofSeconds(1))
        delay(1_500)
        assertThat(repository.take(ticket.value)).isNull()
    }

    @Test
    fun `I1 티켓 1장에 take 100개를 동시에 — 값을 받은 호출은 정확히 1`(): Unit = runBlocking {
        val ticket = newTicket()
        repository.saveNew(ticket, RealtimeTicket.TTL)
        val gate = CompletableDeferred<Unit>()
        val results = List(100) {
            async(Dispatchers.IO) {
                gate.await()
                repository.take(ticket.value)
            }
        }
        gate.complete(Unit)
        val taken = results.awaitAll()
        assertThat(taken.filterNotNull()).containsExactly(ticket)
        assertThat(taken.count { it == null }).isEqualTo(99)
    }
}
