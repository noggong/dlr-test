package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.app.realtime.sse.GroupChannelConnection
import com.delightroom.mission.app.realtime.sse.GroupChannelHub
import com.delightroom.mission.app.realtime.sse.SseConnectionRegistry
import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.event.GroupChannelType
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import com.delightroom.mission.infra.redis.GroupChannelCodec
import com.delightroom.mission.infra.redis.RedisKeys
import io.lettuce.core.KillArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * ORACLE realtime-board I3 · I4 — 노드 안 그룹 구독은 하나이고, 연결이 0 이 되면 풀리며, 해제 · 구독 경합 중 붙은 연결도 다음 메시지를 받는다.
 * 메시지는 신호 Lua 를 부르지 않고 SPEC 예시 4명 그룹 Hash 로 직접 `SPUBLISH` 한다(ORACLE 테스트 규약).
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupChannelHubTest
    @Autowired
    constructor(
        private val hub: GroupChannelHub,
        private val registry: SseConnectionRegistry,
        private val keys: RedisKeys,
        private val codec: GroupChannelCodec,
        connectionFactory: ReactiveRedisConnectionFactory,
    ) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun redis(registry: DynamicPropertyRegistry) = RealtimeTestRedis.register(registry)
        }

        private val connection: StatefulRedisConnection<String, String> =
            ((connectionFactory as LettuceConnectionFactory).requiredNativeClient as RedisClient).connect()
        private val redis = connection.sync()

        @AfterAll
        fun close() = connection.close()

        private fun shardSubscribers(gid: GroupId): Long = redis.pubsubShardNumsub(keys.channel(gid)).values.single()

        private fun publish(gid: GroupId, version: Long) {
            val hash = BoardFixtures.inProgressHash() + ("version" to "$version")
            val raw = codec.encode(
                GroupChannelType.SOLVE_APPLIED, gid, BoardFixtures.DATE, "$version-0", UserId(BoardFixtures.JUNHO),
                version, BoardFixtures.JUNHO_LS, hash,
            )
            redis.spublish(keys.channel(gid), raw)
        }

        private suspend fun GroupChannelConnection.next(): GroupChannelMessage = withTimeout(5_000) { messages.first() }

        private suspend fun attachAll(gid: GroupId, n: Int): List<GroupChannelConnection> =
            coroutineScope { (1..n).map { async(Dispatchers.Default) { hub.attach(gid) } }.awaitAll() }

        private suspend fun detachAll(connections: List<GroupChannelConnection>) =
            coroutineScope { connections.map { async(Dispatchers.Default) { hub.detach(it) } }.awaitAll() }

        @Test
        fun `I3 · I4 — 200연결이어도 구독 1, 끊는 중 붙은 50연결이 다음 메시지를 받고, 다 끊으면 구독 0 · 연결 수 0`(): Unit = runBlocking {
            val gid = GroupId(2_001)

            // I3: 같은 그룹에 200연결 동시 attach
            val first = attachAll(gid, 200)
            assertThat(shardSubscribers(gid)).isEqualTo(1)
            assertThat(registry.connectionCount(gid)).isEqualTo(200)
            publish(gid, 18)
            first.forEach { assertThat(it.next().version).isEqualTo(18) }

            // I4: 200연결을 끊는다 — 150개를 먼저 끊고, 남은 50개를 끊는 동안 새 연결 50개가 붙는다(경합)
            detachAll(first.take(150))
            val fresh = coroutineScope {
                val detaching = first.drop(150).map { async(Dispatchers.Default) { hub.detach(it) } }
                val attaching = (1..50).map { async(Dispatchers.Default) { hub.attach(gid) } }
                detaching.awaitAll()
                attaching.awaitAll()
            }
            assertThat(shardSubscribers(gid)).isEqualTo(1)
            assertThat(registry.connectionCount(gid)).isEqualTo(50)
            first.forEach { assertThat(withTimeout(5_000) { it.messages.toList() }).isEmpty() } // 뗀 연결은 정상으로 끝났다

            publish(gid, 19)
            fresh.forEach { assertThat(it.next().version).isEqualTo(19) }

            detachAll(fresh)
            assertThat(shardSubscribers(gid)).isZero()
            assertThat(registry.connectionCount(gid)).isZero()
            assertThat(registry.connectionCount()).isZero()
        }

        @Test
        fun `I4 — 마지막 연결이 끊기며 구독을 푸는 중에 붙은 연결도 다음 메시지를 받는다(30회)`(): Unit = runBlocking {
            val gid = GroupId(2_002)
            var current = hub.attach(gid)
            repeat(30) { round ->
                val leaving = current
                current = coroutineScope {
                    val detaching = async(Dispatchers.Default) { hub.detach(leaving) }
                    val attaching = async(Dispatchers.Default) { hub.attach(gid) }
                    detaching.await()
                    attaching.await()
                }
                assertThat(shardSubscribers(gid)).isEqualTo(1)
                publish(gid, 100L + round)
                assertThat(current.next().version).isEqualTo(100L + round)
            }
            hub.detach(current)
            hub.detach(current) // 두 번 떼도 한 번
            assertThat(shardSubscribers(gid)).isZero()
            assertThat(registry.connectionCount(gid)).isZero()
        }

        @Test
        fun `구독 연결이 끊기면 그 그룹 연결이 모두 GroupChannelLostException 으로 끝나고, 새 연결은 다시 구독해 받는다`(): Unit = runBlocking {
            val gid = GroupId(2_003)
            val connections = attachAll(gid, 3)

            redis.clientKill(KillArgs.Builder.typePubsub())
            connections.forEach {
                val failure = runCatching { withTimeout(5_000) { it.messages.toList() } }.exceptionOrNull()
                assertThat(failure).isInstanceOf(GroupChannelLostException::class.java)
            }
            detachAll(connections)
            assertThat(registry.connectionCount(gid)).isZero()

            val again = hub.attach(gid)
            assertThat(shardSubscribers(gid)).isEqualTo(1)
            publish(gid, 30)
            assertThat(again.next().version).isEqualTo(30)
            hub.detach(again)
            assertThat(shardSubscribers(gid)).isZero()
            assertThat(registry.connectionCount()).isZero()
        }
    }
