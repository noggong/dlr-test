package com.delightroom.mission.infra.redis.realtimeboard

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.event.GroupChannelType
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.infra.redis.GroupChannelCodec
import com.delightroom.mission.infra.redis.RedisGroupEventSubscriber
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisTestContainer
import io.lettuce.core.KillArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Instant

/**
 * 그룹 채널 코덱(LLD realtime-board §2.3) · `SSUBSCRIBE` 구독(LLD §1 · §5) — ORACLE realtime-board 확인 명령 2 묶음.
 * 메시지는 신호 Lua 를 부르지 않고 SPEC 예시 4명 그룹 Hash 로 직접 `SPUBLISH` 한다(ORACLE 테스트 규약).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupEventSubscriberTest {
    private val keys = RedisKeys(GameProperties())
    private val codec = GroupChannelCodec()
    private val factory = RedisTestContainer.template.connectionFactory as LettuceConnectionFactory
    private val subscriber = RedisGroupEventSubscriber(factory, keys, codec)
    private val connection = (factory.requiredNativeClient as RedisClient).connect()
    private val redis: RedisCommands<String, String> = connection.sync()

    @AfterAll
    fun close() {
        subscriber.destroy()
        connection.close()
    }

    private fun message(gid: GroupId, version: Long = BoardFixtures.VERSION, hash: Map<String, String> = BoardFixtures.inProgressHash()) =
        codec.encode(
            type = GroupChannelType.SOLVE_APPLIED,
            groupId = gid,
            date = BoardFixtures.DATE,
            streamId = "1762898621120-0",
            changedUserId = UserId(BoardFixtures.JUNHO),
            version = version,
            atMicros = BoardFixtures.JUNHO_LS,
            hash = hash + ("version" to "$version"),
        )

    private fun shardSubscribers(gid: GroupId): Long = redis.pubsubShardNumsub(keys.channel(gid)).values.single()

    @Test
    fun `코덱 — Lua 모양 JSON 을 읽고 시각 · id 는 문자열로 쓴다`() {
        val raw = message(GroupId(7))
        assertThat(raw).contains(""""at":"${BoardFixtures.JUNHO_LS}"""", """"gid":"7"""", """"uid":"${BoardFixtures.JUNHO}"""")

        val decoded = codec.decode(raw)
        assertThat(decoded.type).isEqualTo(GroupChannelType.SOLVE_APPLIED)
        assertThat(decoded.groupId).isEqualTo(GroupId(7))
        assertThat(decoded.date).isEqualTo(BoardFixtures.DATE)
        assertThat(decoded.streamId).isEqualTo("1762898621120-0")
        assertThat(decoded.changedUserId).isEqualTo(UserId(BoardFixtures.JUNHO))
        assertThat(decoded.version).isEqualTo(BoardFixtures.VERSION)
        assertThat(GroupChannelCodec.micros(decoded.at)).isEqualTo(BoardFixtures.JUNHO_LS)
        assertThat(decoded.snapshot.members).hasSize(4)
    }

    @Test
    fun `코덱 — 모르는 key 는 무시하고 version 이 없으면 hash 의 version, uid · streamId 는 null 가능`() {
        val hash = codec.encode(GroupChannelType.GROUP_PENDING, GroupId(7), BoardFixtures.DATE, null, null, 0, 1, BoardFixtures.inProgressHash())
            .substringAfter(""""hash":""").dropLast(1)
        val raw = """{"type":"group.pending","streamId":null,"date":"2026-11-12","gid":"7","uid":null,"at":"1","extra":[1,2],"hash":$hash}"""

        val decoded = codec.decode(raw)
        assertThat(decoded.version).isEqualTo(BoardFixtures.VERSION)
        assertThat(decoded.streamId).isNull()
        assertThat(decoded.changedUserId).isNull()
        assertThat(decoded.at).isEqualTo(Instant.ofEpochSecond(0, 1_000))
    }

    @Test
    fun `코덱 — 파싱 실패 · hash 없음 · 모르는 type · 틀린 Hash 는 IllegalArgumentException`() {
        val good = message(GroupId(7))
        val broken = listOf(
            "not json",
            "[]",
            good.replace(""""hash":""", """"hush":"""),
            good.replace("solve.applied", "solve.unknown"),
            good.replace(""""${BoardFixtures.JUNHO}:p":"2"""", """"${BoardFixtures.JUNHO}:p":"9""""),
            good.replace(""""gid":"7"""", """"gid":"x""""),
            good.replace(""""date":"2026-11-12"""", """"date":"2026-13-40""""),
        )
        broken.forEach { raw -> assertThatThrownBy { codec.decode(raw) }.`as`(raw).isInstanceOf(IllegalArgumentException::class.java) }
    }

    @Test
    fun `구독 확인 뒤 SHARDNUMSUB = 1 이고 발행한 메시지를 받는다, 취소하면 0`(): Unit = runBlocking {
        val gid = GroupId(1_001)
        val subscribed = CompletableDeferred<Unit>()
        val received = Channel<GroupChannelMessage>(Channel.UNLIMITED)
        val job = launch { subscriber.subscribe(gid) { subscribed.complete(Unit) }.collect { received.send(it) } }
        withTimeout(5_000) { subscribed.await() }
        assertThat(shardSubscribers(gid)).isEqualTo(1)

        redis.spublish(keys.channel(gid), message(gid, version = 18))
        val got = withTimeout(5_000) { received.receive() }
        assertThat(got.groupId).isEqualTo(gid)
        assertThat(got.version).isEqualTo(18)

        job.cancelAndJoin() // 해제 확인까지 기다린 뒤 끝난다
        assertThat(shardSubscribers(gid)).isZero()
    }

    @Test
    fun `잘못된 메시지는 버리고 다음 메시지는 받는다`(): Unit = runBlocking {
        val gid = GroupId(1_002)
        val subscribed = CompletableDeferred<Unit>()
        val received = Channel<GroupChannelMessage>(Channel.UNLIMITED)
        val job = launch { subscriber.subscribe(gid) { subscribed.complete(Unit) }.collect { received.send(it) } }
        withTimeout(5_000) { subscribed.await() }

        redis.spublish(keys.channel(gid), "not json")
        redis.spublish(keys.channel(gid), """{"type":"solve.applied"}""")
        redis.spublish(keys.channel(gid), message(gid, version = 19))
        assertThat(withTimeout(5_000) { received.receive() }.version).isEqualTo(19)
        job.cancelAndJoin()
    }

    @Test
    fun `같은 그룹을 동시에 두 번 구독하면 IllegalStateException`(): Unit = runBlocking {
        val gid = GroupId(1_003)
        val subscribed = CompletableDeferred<Unit>()
        val first = launch { subscriber.subscribe(gid) { subscribed.complete(Unit) }.collect {} }
        withTimeout(5_000) { subscribed.await() }

        assertThatThrownBy { runBlocking { subscriber.subscribe(gid) {}.collect {} } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(shardSubscribers(gid)).isEqualTo(1) // 먼저 한 구독은 그대로다
        first.cancelAndJoin()
        assertThat(shardSubscribers(gid)).isZero()
    }

    @Test
    fun `구독 연결이 끊기면 모든 구독이 GroupChannelLostException 으로 끝나고, 다음 구독은 새 연결로 받는다`(): Unit = runBlocking {
        val gids = listOf(GroupId(1_004), GroupId(1_005))
        val outcomes = coroutineScope {
            val subscribedAll = gids.map { CompletableDeferred<Unit>() }
            val running = gids.mapIndexed { i, gid ->
                async { runCatching { subscriber.subscribe(gid) { subscribedAll[i].complete(Unit) }.collect {} } }
            }
            withTimeout(5_000) { subscribedAll.forEach { it.await() } }
            redis.clientKill(KillArgs.Builder.typePubsub())
            withTimeout(5_000) { running.map { it.await() } }
        }
        outcomes.forEach { assertThat(it.exceptionOrNull()).isInstanceOf(GroupChannelLostException::class.java) }

        val gid = gids.first()
        val subscribed = CompletableDeferred<Unit>()
        val received = Channel<GroupChannelMessage>(Channel.UNLIMITED)
        val job = launch { subscriber.subscribe(gid) { subscribed.complete(Unit) }.collect { received.send(it) } }
        withTimeout(5_000) { subscribed.await() }
        redis.spublish(keys.channel(gid), message(gid, version = 20))
        assertThat(withTimeout(5_000) { received.receive() }.version).isEqualTo(20)
        job.cancelAndJoin()
        assertThat(shardSubscribers(gid)).isZero()
    }
}
