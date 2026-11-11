package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.GroupEventSubscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** SSE 연결 하나가 받는 그룹 채널 메시지. [GroupChannelHub.attach] 가 만들고 [GroupChannelHub.detach] 로 돌려준다. */
class GroupChannelConnection internal constructor(
    val groupId: GroupId,
    bufferSize: Int,
) {
    private val buffer = Channel<GroupChannelMessage>(bufferSize, BufferOverflow.DROP_OLDEST)
    internal val detached = AtomicBoolean(false)
    internal lateinit var entry: GroupChannelHub.Entry

    /**
     * attach 가 돌아온 뒤 온 메시지(연결 버퍼 — 넘치면 가장 오래된 것을 버린다. 메시지마다 상태 전체라 다음 것이 메운다).
     * detach 하면 정상으로 끝나고, 구독이 끊기면 [GroupChannelLostException] 으로 끝난다. 한 번만 수집한다.
     */
    val messages: Flow<GroupChannelMessage> = buffer.receiveAsFlow()

    internal fun offer(message: GroupChannelMessage) {
        buffer.trySend(message)
    }

    internal fun close(cause: Throwable?) {
        buffer.close(cause)
    }
}

/**
 * 노드 안에서 그룹마다 채널 구독 하나를 공유한다(LLD realtime-board §4.4 · ORACLE I3 · I4).
 *
 * 그룹의 첫 연결이 구독을 열고(확인까지 기다린다) 마지막 연결이 구독을 푼다(해제 확인까지 기다린다). attach · detach 는 gid 별
 * [Mutex](gid 해시로 고른 줄무늬)로 줄 세운다 — 「구독 해제 중 새 연결」에서 구독이 둘 생기거나 연결이 구독 없이 남지 않는다.
 * 받은 메시지는 파싱된 채로 연결마다의 버퍼에 막지 않고 나눠 준다. 구독이 끊기면 그 그룹 연결을 모두 그 예외로 닫는다.
 */
@Component
class GroupChannelHub(
    private val subscriber: GroupEventSubscriber,
    private val registry: SseConnectionRegistry,
    @param:Value("\${realtime.connection-buffer:16}") private val bufferSize: Int,
) : DisposableBean {
    /** 그룹 구독 하나와 그것을 나눠 받는 연결들. [connections] 추가와 [closeAll] 은 이 객체로 동기화한다. */
    internal class Entry(private val groupId: GroupId) {
        private val connections = CopyOnWriteArraySet<GroupChannelConnection>()
        private var closed = false
        lateinit var job: Job

        /** 이미 끊긴 구독이면 false. */
        @Synchronized
        fun add(connection: GroupChannelConnection): Boolean {
            if (closed) return false
            connections += connection
            connection.entry = this
            return true
        }

        /** 마지막 연결이었으면 true. */
        @Synchronized
        fun remove(connection: GroupChannelConnection): Boolean = connections.remove(connection) && connections.isEmpty()

        fun fanOut(message: GroupChannelMessage) {
            if (message.groupId != groupId) return // 채널 이름이 gid 로 정해지므로 오지 않는다 — 와도 남의 그룹에 흘리지 않는다
            connections.forEach { it.offer(message) }
        }

        @Synchronized
        fun closeAll(cause: Throwable?) {
            closed = true
            connections.forEach { it.close(cause) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("group-channel-hub"))
    private val entries = ConcurrentHashMap<GroupId, Entry>()
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    /** [groupId] 채널을 나눠 받는 연결을 만든다. 돌아온 뒤 발행된 메시지는 이 연결에 반드시 온다. 구독을 열지 못하면 [GroupChannelLostException]. */
    suspend fun attach(groupId: GroupId): GroupChannelConnection =
        lockOf(groupId).withLock {
            val connection = GroupChannelConnection(groupId, bufferSize)
            val existing = entries[groupId]
            if (existing == null || !existing.add(connection)) {
                if (existing != null) entries.remove(groupId, existing)
                subscribe(groupId, connection)
            }
            registry.register(groupId)
            connection
        }

    /**
     * 연결을 뗀다(두 번 불러도 한 번). 그 그룹의 마지막 연결이면 구독을 풀고 해제 확인까지 기다린다.
     * 스트림이 취소된 뒤 `finally` 에서 부르므로 취소 중에도 끝까지 한다 — 중간에 멈추면 참조 수가 0 이 되지 않아 구독이 남는다.
     */
    suspend fun detach(connection: GroupChannelConnection): Unit =
        withContext(NonCancellable) {
            val groupId = connection.groupId
            lockOf(groupId).withLock {
                if (!connection.detached.compareAndSet(false, true)) return@withLock
                connection.close(null)
                val entry = connection.entry
                if (entry.remove(connection) && entries.remove(groupId, entry)) entry.job.cancelAndJoin()
                registry.unregister(groupId)
            }
        }

    override fun destroy() {
        scope.cancel()
    }

    /** lock 을 쥔 채 부른다 — 새 구독을 열고 확인까지 기다린 뒤 [entries] 에 올린다. */
    private suspend fun subscribe(groupId: GroupId, first: GroupChannelConnection) {
        val entry = Entry(groupId)
        entry.add(first)
        val subscribed = CompletableDeferred<Unit>()
        entry.job = scope.launch {
            try {
                subscriber.subscribe(groupId) { subscribed.complete(Unit) }.collect(entry::fanOut)
                throw GroupChannelLostException("그룹 채널 구독이 끝났다: ${groupId.value}")
            } catch (e: CancellationException) {
                entry.closeAll(null)
                throw e
            } catch (e: Throwable) {
                val lost = e as? GroupChannelLostException ?: GroupChannelLostException("그룹 채널 구독 실패: ${groupId.value}", e)
                subscribed.completeExceptionally(lost)
                entries.remove(groupId, entry)
                entry.closeAll(lost)
            }
        }
        try {
            subscribed.await()
        } catch (e: Throwable) {
            // 취소된 attach 도 구독 Job 이 해제까지 끝난 뒤 lock 을 놓는다 — 다음 attach 가 반쯤 남은 구독과 겹치지 않게
            first.detached.set(true)
            withContext(NonCancellable) { entry.job.cancelAndJoin() }
            throw e
        }
        entries[groupId] = entry
    }

    private fun lockOf(groupId: GroupId): Mutex = locks[Math.floorMod(groupId.value.hashCode(), LOCK_STRIPES)]

    private companion object {
        const val LOCK_STRIPES = 256
    }
}
