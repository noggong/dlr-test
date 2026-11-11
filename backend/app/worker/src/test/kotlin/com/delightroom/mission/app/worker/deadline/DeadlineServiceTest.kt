package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.deadline.DeadlineFixture.Companion.EARLY_FIELDS
import com.delightroom.mission.app.worker.deadline.DeadlineFixture.Companion.decided
import com.delightroom.mission.app.worker.deadline.DeadlineFixture.Companion.early
import com.delightroom.mission.app.worker.deadline.DeadlineFixture.Companion.pending
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.service.DeadlineService
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.ContextConfiguration
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * ORACLE deadline-result O16 · I1 ~ I5 — `DeadlineService.runPhase` 를 Testcontainers Redis 에 돌린다.
 * 그룹 Hash · 명단 · `day:` Set 은 [DeadlineFixture] 가 직접 쓰고, 유예 풀이는 solve-signal 의 Lua `solve` 를 그대로 부른다.
 * 앱 컨텍스트는 Redis 연결만 빌린다 — persister · 스케줄러는 끈다(같은 Redis 의 Stream 을 건드리지 않게).
 */
@SpringBootTest(properties = ["worker.persister.enabled=false", "worker.deadline.enabled=false"])
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeadlineServiceTest
    @Autowired
    constructor(
        private val redis: ReactiveStringRedisTemplate,
    ) {
        private enum class Kind { DECIDED, PENDING, EARLY, SOLO }

        /** 그룹 5,000개(gid 가 이어져 1,024 버킷을 고루 채운다) — 종류는 [kindOf]. */
        private fun groups(f: DeadlineFixture, kindOf: (Int) -> Kind): Map<GroupId, Kind> {
            val base = 1024L * Random.nextLong(1_000, 1_000_000_000)
            return (0 until GROUPS).associate { GroupId(base + it) to kindOf(it) }
        }

        private suspend fun write(f: DeadlineFixture, groups: Map<GroupId, Kind>) {
            val ls = f.us(LocalTime.of(7, 30))
            f.write(
                groups.mapValues { (gid, kind) ->
                    val uid0 = gid.value * 10
                    when (kind) {
                        Kind.DECIDED -> decided(uid0, ls)
                        Kind.PENDING -> pending(uid0, ls)
                        Kind.EARLY -> early(uid0, ls)
                        Kind.SOLO -> listOf(DeadlineFixture.M(uid0, "s$uid0", "NOT_STARTED", -1))
                    }
                },
            ) { gid -> if (groups[gid] == Kind.EARLY) EARLY_FIELDS else emptyMap() }
        }

        @Test
        fun `O16 인스턴스 2개가 버킷을 512개씩 나눠 맡고 모든 그룹을 한 번씩 처리한다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val groups = groups(f) { i ->
                when (i % 8) {
                    0, 1, 2, 3 -> Kind.DECIDED
                    4, 5 -> Kind.PENDING
                    6 -> Kind.EARLY
                    else -> Kind.SOLO
                }
            }
            write(f, groups)
            // 1차 창(08:00:00 ~ 유예 끝)을 넉넉히 — 처리 도중 유예가 끝나 DECIDED_FINAL 로 바뀌지 않게
            val game = f.at(Duration.ofMillis(500), submitGrace = Duration.ofSeconds(60))
            val services = (0..1).map { f.service(game, DeadlineService.Options(instanceIndex = it, instanceCount = 2)) }

            val reports = coroutineScope { services.map { async { it.runPhase(f.date, DeadlinePhase.FIRST) } }.awaitAll() }

            assertThat(services.map { it.buckets.size }).containsExactly(512, 512)
            assertThat(services[0].buckets.intersect(services[1].buckets.toSet())).isEmpty()
            assertThat(reports.map { it.buckets }).containsExactly(512, 512)
            assertThat(reports.flatMap { it.failedGroups + it.failedBuckets }).isEmpty()
            val counts = DeadlineCode.entries.associateWith { code -> reports.sumOf { it.count(code) } }.filterValues { it > 0 }
            val expected = groups.values.groupingBy { it }.eachCount()
            assertThat(counts).isEqualTo(
                mapOf(
                    DeadlineCode.DECIDED to expected[Kind.DECIDED],
                    DeadlineCode.PENDING to expected[Kind.PENDING],
                    DeadlineCode.SKIPPED to expected[Kind.EARLY],
                    DeadlineCode.NO_GROUP to expected[Kind.SOLO],
                ),
            )
            assertThat(reports.sumOf { it.groups }).isEqualTo(GROUPS)

            val events = f.events()
            val hashes = coroutineScope { groups.keys.map { gid -> async { gid to f.hash(gid) } }.awaitAll() }.toMap()
            groups.forEach { (gid, kind) ->
                val h = hashes.getValue(gid)
                val decidedEvents = events[gid.value].orEmpty().filter { it["type"] == "group.decided" }
                when (kind) {
                    Kind.SOLO -> assertThat(h).isEmpty()
                    else -> assertThat(h.containsKey("result") || h["pending"] == "1").`as`("gid ${gid.value} result 또는 pending").isTrue()
                }
                assertThat(decidedEvents).`as`("gid ${gid.value} group.decided").hasSize(if (kind == Kind.DECIDED) 1 else 0)
            }
        }

        // ---- I1 ~ I5: 1차 · 2차 · 유예 solve 를 겹쳐 돈 한 번의 실행을 여러 기준으로 본다 ----

        private inner class Overlap {
            val f = DeadlineFixture(redis)
            val groups = groups(f) { i -> if (i % 4 == 0) Kind.DECIDED else if (i % 4 == 3) Kind.EARLY else Kind.PENDING }
            val hashGids = groups.keys.toList()
            lateinit var game: GameProperties
            lateinit var service: DeadlineService

            /** 표본 `HMGET pending result rankingFinal` 결과. */
            val samples = AtomicLong()
            val pendingWithResult = ConcurrentLinkedQueue<Long>()
            val rankingFinalDown = ConcurrentLinkedQueue<Long>()
            val sawFinal = ConcurrentHashMap.newKeySet<Long>()
            val firstResult = ConcurrentHashMap<Long, String>()

            /** gid → 받은 채널 메시지 `version`(받은 순서). */
            val versions = ConcurrentHashMap<Long, ConcurrentLinkedQueue<Long>>()
            val solveCodes = ConcurrentHashMap<String, AtomicInteger>()

            lateinit var hashes: Map<GroupId, Map<String, String>>
            lateinit var events: Map<Long, List<Map<String, String>>>

            fun run() = runBlocking {
                write(f, groups)
                val client = (redis.connectionFactory as LettuceConnectionFactory).requiredNativeClient as RedisClient
                val pubsub = client.connectPubSub()
                val channelGid = hashGids.associateBy { f.keys.channel(it) }
                pubsub.addListener(object : RedisPubSubAdapter<String, String>() {
                    override fun smessage(channel: String, message: String) {
                        val gid = channelGid[channel] ?: return
                        val v = requireNotNull(VERSION.find(message)) { "version 없음: $message" }.groupValues[1].toLong()
                        versions.computeIfAbsent(gid.value) { ConcurrentLinkedQueue() } += v
                    }
                })
                pubsub.sync().ssubscribe(*channelGid.keys.toTypedArray())
                try {
                    // 지금 = 08:00:00.3 — 유예 끝(08:00:03)까지 약 2.7초 동안 1차 · 유예 solve 가 겹치고, 2차는 EARLY 로 기다렸다 이어진다
                    game = f.at(Duration.ofMillis(300))
                    service = f.service(game)
                    val clickedAt = f.micros(game.deadlineAt(f.date)) - 100_000L
                    coroutineScope {
                        val done = Channel<Unit>()
                        val sampler = launch { sample(done) }
                        val work = listOf(
                            async { service.runPhase(f.date, DeadlinePhase.FIRST) },
                            async { service.runPhase(f.date, DeadlinePhase.SECOND) },
                        )
                        val solves = Channel<GroupId>(Channel.UNLIMITED)
                        groups.filterValues { it != Kind.EARLY }.keys.shuffled().forEach { solves.send(it) }
                        solves.close()
                        val solvers = (1..64).map {
                            launch {
                                for (gid in solves) {
                                    val (no, qid) = if (groups[gid] == Kind.DECIDED) 2 to "q2" else 3 to "q3"
                                    val code = f.solve(game, gid, gid.value * 10, no, qid, clickedAt)
                                    solveCodes.computeIfAbsent(code) { AtomicInteger() }.incrementAndGet()
                                }
                            }
                        }
                        val reports = work.awaitAll()
                        solvers.forEach { it.join() }
                        done.close()
                        sampler.join()
                        assertThat(reports.flatMap { it.failedGroups + it.failedBuckets }).isEmpty()
                    }
                    // 채널 메시지가 다 올 때까지 — 받은 수가 0.5초 동안 그대로면 끝
                    var last = -1
                    while (true) {
                        val now = versions.values.sumOf { it.size }
                        if (now == last) break
                        last = now
                        delay(500)
                    }
                } finally {
                    pubsub.close()
                }
                hashes = coroutineScope { hashGids.map { gid -> async { gid to f.hash(gid) } }.awaitAll() }.toMap()
                events = f.events()
            }

            /** I4 · I2 — 1ms 마다 표본 100개. */
            private suspend fun sample(done: Channel<Unit>) = coroutineScope {
                while (!done.isClosedForReceive) {
                    hashGids.shuffled().take(100).map { gid ->
                        async {
                            val v = redis.opsForHash<String, String>()
                                .multiGet(f.keys.group(gid, f.date), listOf("pending", "result", "rankingFinal")).awaitSingle()
                            gid.value to v
                        }
                    }.awaitAll().forEach { (gid, v) ->
                        samples.incrementAndGet()
                        val pending: String? = v[0]
                        val result: String? = v[1]
                        val rankingFinal: String? = v[2]
                        if (pending == "1" && result != null) pendingWithResult += gid
                        if (result != null) firstResult.putIfAbsent(gid, result)
                        if (rankingFinal == "1") sawFinal += gid
                        if (rankingFinal == "0" && gid in sawFinal) rankingFinalDown += gid
                    }
                    delay(1)
                }
            }
        }

        private val overlap by lazy { Overlap().also { it.run() } }

        @Test
        fun `I1 1차 · 2차 · 유예 solve 가 겹쳐도 그룹마다 group_decided 는 한 번 이하이고 result 는 처음 값 그대로다`() {
            val o = overlap
            println("I1 solve 반환 코드 = ${o.solveCodes}")
            // 겹침이 실제로 일어났다 — 유예 안에 반영된 풀이와 유예 뒤 거절된 풀이가 섞인다
            assertThat(o.solveCodes.keys).contains("OK")
            o.groups.forEach { (gid, kind) ->
                val h = o.hashes.getValue(gid)
                val decided = o.events[gid.value].orEmpty().filter { it["type"] == "group.decided" }
                assertThat(decided.size).`as`("gid ${gid.value} group.decided").isLessThanOrEqualTo(1)
                assertThat(h["result"]).`as`("gid ${gid.value} result").isNotNull()
                if (kind == Kind.EARLY) {
                    assertThat(h["result"]).isEqualTo("SUCCEEDED")
                    assertThat(decided).isEmpty()
                } else {
                    assertThat(decided).`as`("gid ${gid.value} 결과를 쓴 group.decided").hasSize(1)
                    assertThat(decided.single()["result"]).isEqualTo(h["result"])
                }
                o.firstResult[gid.value]?.let { assertThat(h["result"]).`as`("gid ${gid.value} 처음 본 result").isEqualTo(it) }
            }
        }

        @Test
        fun `I2 rankingFinal 은 0 에서 1 로만 가고 1 뒤 2차 재실행 · 유예 밖 solve 에도 랭킹이 그대로다`(): Unit = runBlocking {
            val o = overlap
            assertThat(o.rankingFinalDown).isEmpty()
            assertThat(o.hashes.values.map { it["rankingFinal"] }.toSet()).containsExactly("1")

            val again = o.service.runPhase(o.f.date, DeadlinePhase.SECOND)
            assertThat(again.counts).isEqualTo(mapOf(DeadlineCode.SKIPPED to GROUPS))
            // 유예 밖 풀이: 확정 그룹의 a 에게 다음 문제(p+1)를 보내 상태 · 순서 검사를 지나 시간 판정에서 거절되게 한다
            val clickedAt = o.f.micros(o.game.graceEndAt(o.f.date)) + 1
            val late = o.groups.filterValues { it == Kind.DECIDED }.keys.map { gid ->
                val a = gid.value * 10
                val p = o.hashes.getValue(gid).getValue("$a:p").toInt()
                o.f.solve(o.game, gid, a, p + 1, "q${p + 1}", clickedAt)
            }
            assertThat(late.toSet()).containsExactly("SUBMISSION_CLOSED")

            o.groups.keys.forEach { gid ->
                val h = o.f.hash(gid)
                val before = o.hashes.getValue(gid)
                assertThat(h["rankingFinal"]).isEqualTo("1")
                assertThat(h["ranking"]).`as`("gid ${gid.value} ranking 바이트").isEqualTo(before["ranking"])
                assertThat(h["version"]).isEqualTo(before["version"])
            }
        }

        @Test
        fun `I3 확정된 그룹에는 NOT_STARTED · IN_PROGRESS 그룹원이 없다`() {
            val o = overlap
            o.hashes.values.filter { it.containsKey("result") }.forEach { h ->
                assertThat(h.filterKeys { it.endsWith(":st") }.values).doesNotContain("NOT_STARTED", "IN_PROGRESS")
            }
        }

        @Test
        fun `I4 pending 과 result 가 함께 있는 표본은 0 건이다`() {
            val o = overlap
            assertThat(o.samples.get()).isGreaterThan(1_000)
            assertThat(o.pendingWithResult).isEmpty()
            assertThat(o.hashes.values.filter { it["pending"] == "1" }).isEmpty()
        }

        @Test
        fun `I5 그룹 채널 메시지의 version 은 엄격히 늘고 중복이 없다`() {
            val o = overlap
            val nonEarly = o.groups.filterValues { it != Kind.EARLY }.keys.map { it.value }
            assertThat(o.versions.keys).containsAll(nonEarly)
            o.versions.forEach { (gid, vs) ->
                assertThat(vs.toList().zipWithNext().all { (a, b) -> a < b }).`as`("gid $gid versions $vs").isTrue()
            }
        }

        private companion object {
            const val GROUPS = 5_000
            val VERSION = Regex("\"version\":(\\d+)")
        }
    }
