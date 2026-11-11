package com.delightroom.mission.app.realtime.realtimeboard

import com.delightroom.mission.app.realtime.sse.GroupChannelHub
import com.delightroom.mission.app.realtime.sse.SseConnectionRegistry
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.event.GroupChannelType
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.GroupEventSubscriber
import com.delightroom.mission.core.testfixture.BoardFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORACLE realtime-board I4 의 경합을 결정적으로 만든다 — 구독 · 해제 확인을 가짜 구독자의 문으로 붙잡아 두고
 * 「해제 중 새 연결」 · 「구독 확인 전 취소」 · 「취소된 코루틴의 detach」 · 「첫 구독 실패」를 차례로 지난다.
 */
class GroupChannelHubRaceTest {
    /** 그룹마다 구독 하나를 흉내 낸다. 문([subscribeGate] · [unsubscribeGate])이 닫혀 있으면 확인을 기다린다. */
    private class FakeSubscriber : GroupEventSubscriber {
        val active = ConcurrentHashMap<GroupId, AtomicInteger>()
        val maxActive = AtomicInteger()
        val subscribes = AtomicInteger()
        val sinks = ConcurrentHashMap<GroupId, SendChannel<GroupChannelMessage>>()

        @Volatile var subscribeGate = CompletableDeferred(Unit)

        @Volatile var unsubscribeGate = CompletableDeferred(Unit)

        @Volatile var failNext: Throwable? = null

        override fun subscribe(groupId: GroupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage> =
            callbackFlow {
                failNext?.let { failNext = null; throw it }
                subscribeGate.await()
                val now = active.computeIfAbsent(groupId) { AtomicInteger() }.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                subscribes.incrementAndGet()
                sinks[groupId] = this
                onSubscribed()
                try {
                    awaitClose()
                } finally {
                    withContext(NonCancellable) { unsubscribeGate.await() }
                    sinks.remove(groupId, this)
                    active.getValue(groupId).decrementAndGet()
                }
            }

        fun emit(groupId: GroupId, version: Long) {
            val snapshot = GroupDaySnapshot.parse(BoardFixtures.DATE, BoardFixtures.inProgressHash() + ("version" to "$version"))
            val message = GroupChannelMessage(
                GroupChannelType.SOLVE_APPLIED, groupId, BoardFixtures.DATE, null, null, version, Instant.EPOCH, snapshot,
            )
            check(sinks.getValue(groupId).trySend(message).isSuccess)
        }
    }

    private val subscriber = FakeSubscriber()
    private val registry = SseConnectionRegistry()
    private val hub = GroupChannelHub(subscriber, registry, 16)

    @AfterEach
    fun close() = hub.destroy()

    @Test
    fun `마지막 연결의 구독 해제가 끝나기 전에 온 attach 는 기다렸다가 새 구독으로 붙고 메시지를 받는다`(): Unit = runBlocking {
        val gid = GroupId(3_001)
        val leaving = hub.attach(gid)
        subscriber.unsubscribeGate = CompletableDeferred()

        val detaching = launch(Dispatchers.Default) { hub.detach(leaving) }
        delay(100) // detach 가 lock 을 쥐고 해제 확인을 기다린다
        val attaching = async(Dispatchers.Default) { hub.attach(gid) }
        delay(200)
        assertThat(attaching.isCompleted).isFalse() // 해제 확인 전에는 lock 에서 기다린다
        assertThat(subscriber.subscribes.get()).isEqualTo(1)

        subscriber.unsubscribeGate.complete(Unit)
        val joined = withTimeout(5_000) { attaching.await() }
        detaching.join()
        assertThat(subscriber.subscribes.get()).isEqualTo(2)
        assertThat(subscriber.maxActive.get()).isEqualTo(1) // 구독이 둘 겹친 적이 없다

        subscriber.emit(gid, 21)
        assertThat(withTimeout(5_000) { joined.messages.first() }.version).isEqualTo(21)
        hub.detach(joined)
        assertThat(registry.connectionCount()).isZero()
    }

    @Test
    fun `구독 확인 전에 취소된 attach 는 구독을 남기지 않고, 다음 attach 는 새로 구독한다`(): Unit = runBlocking {
        val gid = GroupId(3_002)
        subscriber.subscribeGate = CompletableDeferred()
        val cancelled = launch(Dispatchers.Default) { hub.attach(gid) }
        delay(100)
        cancelled.cancelAndJoin()
        assertThat(registry.connectionCount(gid)).isZero()
        assertThat(subscriber.active[gid]?.get() ?: 0).isZero()

        subscriber.subscribeGate.complete(Unit)
        val connection = hub.attach(gid)
        subscriber.emit(gid, 22)
        assertThat(withTimeout(5_000) { connection.messages.first() }.version).isEqualTo(22)
        assertThat(subscriber.maxActive.get()).isEqualTo(1)
        hub.detach(connection)
        assertThat(subscriber.active.getValue(gid).get()).isZero()
    }

    @Test
    fun `취소된 스트림의 finally 에서 부른 detach 도 lock 을 기다려 끝까지 뗀다`(): Unit = runBlocking {
        val busy = GroupId(3_003)
        val other = GroupId(3_003 + 256) // 같은 lock 줄무늬
        val busyConnection = hub.attach(busy)
        val otherConnection = hub.attach(other)
        subscriber.unsubscribeGate = CompletableDeferred()
        val holding = launch(Dispatchers.Default) { hub.detach(busyConnection) } // 해제 확인을 기다리며 lock 을 쥔다
        delay(100)

        val stream = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                hub.detach(otherConnection)
            }
        }
        delay(50)
        stream.cancel()
        delay(100)
        subscriber.unsubscribeGate.complete(Unit)
        withTimeout(5_000) {
            holding.join()
            stream.join()
        }
        assertThat(registry.connectionCount()).isZero()
        assertThat(subscriber.active.getValue(other).get()).isZero()
    }

    @Test
    fun `첫 구독이 실패하면 attach 가 실패하고 연결 수 · 구독이 남지 않으며, 다음 attach 는 붙는다`(): Unit = runBlocking {
        val gid = GroupId(3_004)
        subscriber.failNext = IllegalStateException("boom")
        assertThatThrownBy { runBlocking { hub.attach(gid) } }.isInstanceOf(GroupChannelLostException::class.java)
        assertThat(registry.connectionCount()).isZero()

        val connection = hub.attach(gid)
        subscriber.emit(gid, 23)
        assertThat(withTimeout(5_000) { connection.messages.first() }.version).isEqualTo(23)
        hub.detach(connection)
        assertThat(registry.connectionCount()).isZero()
    }
}
