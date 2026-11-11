package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.GroupEventSubscriber
import io.lettuce.core.RedisChannelHandler
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionStateListener
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 그룹 채널 sharded Pub/Sub 구독(LLD realtime-board §1 · §4.4). Spring 의 listener container 는 `SSUBSCRIBE` 를 모르므로
 * Lettuce 네이티브 pub/sub 연결을 쓴다.
 *
 * 연결은 **첫 구독 때** 하나 열어 이 노드의 모든 그룹 채널이 같이 쓴다 — api 앱은 이 빈을 갖지만 구독하지 않으므로 연결하지 않는다.
 * 그 연결이 끊기면 Lettuce 의 자동 재구독에 맡기지 않고 연결을 닫은 뒤 살아 있는 구독을 모두 [GroupChannelLostException] 으로 끝낸다 —
 * 끊긴 사이 메시지를 잃었을 수 있으므로 스트림을 닫아 클라이언트가 snapshot 부터 다시 받게 한다(LLD §5). 다음 구독은 새 연결을 연다.
 */
internal class RedisGroupEventSubscriber(
    private val connectionFactory: ReactiveRedisConnectionFactory,
    private val keys: RedisKeys,
    private val codec: GroupChannelCodec,
) : GroupEventSubscriber, DisposableBean {
    /** 구독 연결 하나와 그 위의 채널별 받는 곳. */
    private class Link(val connection: StatefulRedisPubSubConnection<String, String>) {
        val sinks = ConcurrentHashMap<String, SendChannel<GroupChannelMessage>>()
        val lost = AtomicBoolean(false)
    }

    private val current = AtomicReference<Link?>(null)
    private val opening = Mutex()

    override fun subscribe(groupId: GroupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage> =
        callbackFlow {
            val channel = keys.channel(groupId)
            val link = link()
            check(link.sinks.putIfAbsent(channel, this) == null) { "이 노드가 이미 구독 중인 그룹 채널: $channel" }
            try {
                // lose() 가 sinks 를 훑기 전에 넣었으면 거기서 닫히고, 훑은 뒤면 여기서 걸린다
                if (link.lost.get()) throw GroupChannelLostException("그룹 채널 구독 연결이 끊겼다: $channel")
                confirm(link, "subscribe", channel) { link.connection.async().ssubscribe(channel).await() }
                onSubscribed()
                awaitClose()
            } finally {
                link.sinks.remove(channel, this)
                if (!link.lost.get()) withContext(NonCancellable) { unsubscribe(link, channel) }
            }
        }.buffer(Channel.UNLIMITED) // Lettuce 이벤트 루프에서 trySend 하므로 막지 않는다 — 받는 쪽(GroupChannelHub)도 막지 않고 나눠 준다

    override fun destroy() {
        current.getAndSet(null)?.connection?.close()
    }

    private suspend fun unsubscribe(link: Link, channel: String) {
        runCatching { confirm(link, "unsubscribe", channel) { link.connection.async().sunsubscribe(channel).await() } }
    }

    /**
     * 구독 · 해제 명령의 확인을 [CONFIRM_TIMEOUT_MS] 까지 기다린다. 확인을 못 받으면 Redis 쪽 구독 상태를 알 수 없으므로
     * 연결을 버려 그 연결의 구독을 모두 걷고(LLD §5) 예외를 다시 던진다. 취소는 그대로 흘린다.
     */
    private suspend fun confirm(link: Link, what: String, channel: String, command: suspend () -> Unit) {
        try {
            withTimeout(CONFIRM_TIMEOUT_MS) { command() }
        } catch (e: TimeoutCancellationException) {
            log.warn("realtime.{}_timeout channel={} — 구독 연결을 닫는다", what, channel)
            lose(link, e)
            throw GroupChannelLostException("그룹 채널 $what 확인이 오지 않았다: $channel", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("realtime.{}_failed channel={} — 구독 연결을 닫는다", what, channel, e)
            lose(link, e)
            throw e
        }
    }

    private suspend fun link(): Link {
        current.get()?.takeUnless { it.lost.get() }?.let { return it }
        return opening.withLock {
            current.get()?.takeUnless { it.lost.get() } ?: open().also { current.set(it) }
        }
    }

    private suspend fun open(): Link {
        val factory = connectionFactory as? LettuceConnectionFactory
            ?: error("그룹 채널 구독은 Lettuce 연결 팩토리가 필요하다: ${connectionFactory.javaClass.name}")
        val connection = withContext(Dispatchers.IO) {
            when (val client = factory.requiredNativeClient) {
                is RedisClient -> client.connectPubSub(StringCodec.UTF8)
                is RedisClusterClient -> client.connectPubSub(StringCodec.UTF8)
                else -> error("모르는 Lettuce 클라이언트: ${client.javaClass.name}")
            }
        }
        val link = Link(connection)
        connection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun smessage(shardChannel: String, message: String) = deliver(link, shardChannel, message)
        })
        connection.addListener(object : RedisConnectionStateListener {
            override fun onRedisDisconnected(connection: RedisChannelHandler<*, *>) = lose(link, null)
        })
        log.info("realtime.subscriber_connected")
        return link
    }

    private fun deliver(link: Link, channel: String, raw: String) {
        val sink = link.sinks[channel] ?: return
        val message = try {
            codec.decode(raw)
        } catch (e: IllegalArgumentException) {
            log.warn("realtime.bad_message channel={} reason={}", channel, e.message)
            return
        }
        sink.trySend(message)
    }

    private fun lose(link: Link, cause: Throwable?) {
        if (!link.lost.compareAndSet(false, true)) return
        current.compareAndSet(link, null)
        log.warn("realtime.subscriber_lost channels={}", link.sinks.size)
        link.sinks.forEach { (channel, sink) ->
            sink.close(GroupChannelLostException("그룹 채널 구독 연결이 끊겼다: $channel", cause))
        }
        link.connection.closeAsync()
    }

    private companion object {
        val log = LoggerFactory.getLogger(RedisGroupEventSubscriber::class.java)
        const val CONFIRM_TIMEOUT_MS = 5_000L
    }
}
