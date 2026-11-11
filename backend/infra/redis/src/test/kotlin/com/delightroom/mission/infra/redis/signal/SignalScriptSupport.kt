package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 신호 Lua 테스트 공용 도우미(ORACLE solve-signal §0).
 * 시각: `t` 를 목표 시각에 맞추려고 부르기 직전의 Redis `TIME` 을 읽어 `offsetUs = 목표 − TIME` 으로 넘긴다.
 * 그룹마다 새 gid 를 받아(버킷도 달라진다) 테스트끼리 키가 겹치지 않는다.
 */
open class SignalScriptSupport {
    protected val redis = RedisTestContainer.template
    protected val scripts = RedisScripts(redis)
    protected val props = GameProperties()
    protected val keys = RedisKeys(props)
    protected val date: LocalDate = LocalDate.of(2026, 11, 12)

    protected val openAt = us(LocalTime.of(7, 0))
    protected val deadlineAt = us(LocalTime.of(8, 0))
    protected val graceEndAt = us(LocalTime.of(8, 0, 3))

    init {
        // 첫 호출의 SCRIPT LOAD 왕복이 「오프셋 계산 → TIME」 사이에 끼지 않게 미리 올린다(O12 · I3 의 지연 여유 50 · 99 ms).
        // 명단 없는 키라 NO_ROSTER 로 끝나고 아무것도 쓰지 않는다.
        runBlocking {
            scripts.eval("solve", listOf("warm:g", "warm:m", "warm:e", "warm:c"), listOf("0", "0", "2026-01-01", "0", "0", "0", "0", "1", "x", "0", ""))
            redisNow()
        }
    }

    /** 게임 날짜의 KST 시각 → epoch µs. */
    protected fun us(time: LocalTime): Long {
        val i = ZonedDateTime.of(date, time, props.zone).toInstant()
        return i.epochSecond * 1_000_000 + i.nano / 1_000
    }

    protected fun hms(h: Int, m: Int, s: Int = 0, ms: Int = 0): Long = us(LocalTime.of(h, m, s, ms * 1_000_000))

    /** 클릭 시각 문자열(KST 오프셋 포함) — `successTime` 자리에도 쓴다. */
    protected fun iso(h: Int, m: Int, s: Int = 0): String = ZonedDateTime.of(date, LocalTime.of(h, m, s), props.zone).toOffsetDateTime().toString()

    /** 그룹 하나의 키 묶음 + 픽스처. */
    inner class Group(val gid: Long = nextGid.incrementAndGet()) {
        val id = GroupId(gid)
        val groupKey = keys.group(id, date)
        val membersKey = keys.members(id, date)
        val streamKey = keys.events(id)
        val channel = keys.channel(id)
        val fixture = GroupHashFixture(redis, groupKey, membersKey, gid, date)

        fun write(vararg members: GroupHashFixture.Member, group: Map<String, String> = emptyMap()) = apply { fixture.write(members.toList(), group) }

        fun hash(): Map<String, String> = runBlocking {
            redis.opsForHash<String, String>().entries(groupKey).collectList().awaitSingle().associate { it.key to it.value }
        }

        fun field(name: String): String? = hash()[name]

        fun version(): Long = field("version")?.toLong() ?: 0

        /** 이 그룹의 Stream 메시지(버킷 Stream 에서 gid 로 거른다) — 오래된 것부터. */
        fun events(): List<Map<String, String>> = runBlocking {
            redis.opsForStream<String, String>().range(streamKey, Range.unbounded()).collectList().awaitSingle()
                .map { r -> r.value.mapKeys { it.key.toString() }.mapValues { it.value.toString() } }
                .filter { it["gid"] == gid.toString() }
        }

        /** 형식 검사용 — ARGV 를 그대로 넘긴다(시각 오프셋 0). */
        fun solveRaw(uid: Long, no: String, qid: String, clickedAt: String): List<Any> = runBlocking {
            scripts.eval(
                "solve",
                listOf(groupKey, membersKey, streamKey, channel),
                listOf(uid.toString(), gid.toString(), date.toString(), "0", "0", "0", "0", no, qid, clickedAt, ""),
            )
        }

        fun set(vararg fields: Pair<String, String>) = runBlocking {
            redis.opsForHash<String, String>().putAll(groupKey, fields.toMap()).awaitSingle()
        }

        fun solve(uid: Long, no: Int, qid: String, at: Long, clickedAt: Long = at, successTime: String = "2026-11-12T07:00:00+09:00"): Reply =
            runBlocking { solveSuspend(uid, no, qid, at, clickedAt, successTime) }

        suspend fun solveSuspend(uid: Long, no: Int, qid: String, at: Long, clickedAt: Long = at, successTime: String = "2026-11-12T07:00:00+09:00"): Reply {
            val offset = at - redisNow()
            val r = scripts.eval(
                "solve",
                listOf(groupKey, membersKey, streamKey, channel),
                listOf(
                    uid.toString(), gid.toString(), date.toString(),
                    openAt.toString(), deadlineAt.toString(), graceEndAt.toString(), offset.toString(),
                    no.toString(), qid, clickedAt.toString(), successTime,
                ),
            )
            check(r.size == 6) { "solve 반환은 6칸이다: $r" }
            return Reply(r[0] as String, (r[1] as Long).toInt(), r[2] as String, r[3] as String, r[4] as Long, r[5] as Long)
        }

        fun decide(t: Long): String = runBlocking {
            scripts.eval("decide_probe", listOf(groupKey, membersKey), listOf(t.toString(), deadlineAt.toString())).single() as String
        }

        fun listen(): ChannelProbe = ChannelProbe(channel)
    }

    /** Lua 반환 6칸(LLD §3.1 출력). */
    data class Reply(val code: String, val progress: Int, val status: String, val groupStatus: String, val version: Long, val t: Long) {
        /** API 응답 본문이 되는 값만(코드 · t 제외) — 재시도 비교용. */
        val body get() = listOf(progress, status, groupStatus, version)
    }

    /** 지금 Redis `TIME`(µs). */
    protected suspend fun redisNow(): Long = scripts.eval("platform_now_probe", emptyList(), listOf("0"))[1] as Long

    /** 그룹 채널 sharded 구독(`SSUBSCRIBE`) — 받은 메시지를 모은다. */
    class ChannelProbe(channel: String) : AutoCloseable {
        private val client: RedisClient
        private val connection: io.lettuce.core.pubsub.StatefulRedisPubSubConnection<String, String>
        val messages = CopyOnWriteArrayList<String>()

        init {
            val factory = RedisTestContainer.template.connectionFactory as LettuceConnectionFactory
            client = RedisClient.create(RedisURI.create(factory.hostName, factory.port))
            connection = client.connectPubSub()
            connection.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun smessage(shardChannel: String, message: String) {
                    messages += message
                }
            })
            connection.sync().ssubscribe(channel)
        }

        /** 메시지가 [n] 건이 될 때까지 기다리고, 조금 더 기다려 더 오지 않는지 본 뒤 받은 것을 돌려준다. */
        fun settle(n: Int, timeoutMs: Long = 2_000, quietMs: Long = 200): List<String> {
            val end = System.currentTimeMillis() + timeoutMs
            while (messages.size < n && System.currentTimeMillis() < end) Thread.sleep(10)
            Thread.sleep(quietMs)
            return messages.toList()
        }

        override fun close() {
            connection.close()
            client.shutdown()
        }
    }

    companion object {
        private val nextGid = AtomicLong(System.nanoTime() % 1_000_000 * 1_000)
        val Q1 = "a1".padEnd(64, '0')
        val Q2 = "b2".padEnd(64, '0')
        val Q3 = "c3".padEnd(64, '0')
        val QX = "ff".padEnd(64, '0')
    }
}
