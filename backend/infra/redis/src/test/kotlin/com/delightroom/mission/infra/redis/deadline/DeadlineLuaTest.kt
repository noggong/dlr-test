package com.delightroom.mission.infra.redis.deadline

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlineOutcome
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.testfixture.RankingCases
import com.delightroom.mission.infra.redis.RedisGroupDayRepository
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * ORACLE deadline-result O5 ~ O15 — §1.2 시나리오 S1 ~ S12 를 Lua `deadline` 에 직접 돌린다.
 * 경계 `D`(deadlineUs)는 실행 순간의 Redis `TIME` 기준이다: 1차 `D = TIME − 0.5초` · 2차 `D = TIME − 3.5초` ·
 * 08:00:03 뒤 1차 `D = TIME − 4초`. 유예 풀이는 solve-signal 의 Lua `solve` 가 아직 없으므로 그 결과와 같은 필드를 직접 쓴다.
 */
class DeadlineLuaTest {
    private val redis = RedisTestContainer.template
    private val scripts = RedisScripts(redis)
    private val keys = RedisKeys(GameProperties())
    private val date: LocalDate = RankingCases.file.gameDate
    private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    private val group = keys.group(gid, date)
    private val json = jacksonObjectMapper()

    /** 그룹 채널 구독 — 받은 메시지(JSON 객체)를 쌓는다. */
    private val channelMessages = CopyOnWriteArrayList<Map<String, Any?>>()
    private val pubsub: StatefulRedisPubSubConnection<String, String> = run {
        val client = (redis.connectionFactory as LettuceConnectionFactory).requiredNativeClient as RedisClient
        client.connectPubSub().also { conn ->
            conn.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun smessage(channel: String, message: String) {
                    channelMessages += json.readValue<Map<String, Any?>>(message)
                }
            })
            conn.sync().ssubscribe(keys.channel(gid))
        }
    }

    @AfterEach
    fun cleanUp() = runBlocking {
        pubsub.close()
        redis.delete(group, keys.members(gid, date)).awaitSingle()
        Unit
    }

    // ---- 준비 ----

    /** 그룹원 한 줄(LLD game-session §2.2 필드). `null` 칸은 쓰지 않는다. */
    private data class M(
        val uid: String,
        val lid: String,
        val st: String,
        val p: Int,
        val cur: String? = null,
        val ls: Long? = null,
        val pn: Int? = null,
        val late: Boolean? = if (p >= 0) false else null,
    )

    private fun ls(time: String): Long = RankingCases.epochMicros(LocalTime.parse(time))

    /** 07:00 `roster` 가 만든 모양에 그룹원 상태를 얹는다 · `version` 0. */
    private fun setUp(members: List<M>, extra: Map<String, String> = emptyMap()) = runBlocking {
        val fields = linkedMapOf("date" to date.toString(), "gid" to gid.value.toString(), "size" to members.size.toString(), "version" to "0")
        members.forEach { write(fields, it) }
        fields += extra
        redis.opsForHash<String, String>().putAll(group, fields).awaitSingle()
        val roster = linkedMapOf("#size" to members.size.toString(), "#name" to "g$gid")
        members.forEach { roster[it.uid] = it.lid }
        redis.opsForHash<String, String>().putAll(keys.members(gid, date), roster).awaitSingle()
    }

    private fun write(fields: MutableMap<String, String>, m: M) {
        fields["${m.uid}:lid"] = m.lid
        fields["${m.uid}:st"] = m.st
        fields["${m.uid}:p"] = m.p.toString()
        m.cur?.let { fields["${m.uid}:cur"] = it }
        m.ls?.let { fields["${m.uid}:ls"] = it.toString() }
        m.pn?.let { fields["${m.uid}:pn"] = it.toString() }
        m.late?.let { fields["${m.uid}:late"] = if (it) "1" else "0" }
    }

    // ---- 실행 ----

    private fun redisTimeUs(): Long = runBlocking { scripts.eval("platform_now_probe", emptyList(), listOf("0"))[1] as Long }

    private fun run(phase: Int, deadlineUs: Long, targetGid: GroupId = gid): DeadlineOutcome = runBlocking {
        val reply = scripts.eval(
            "deadline",
            listOf(keys.group(targetGid, date), keys.members(targetGid, date), keys.events(targetGid)),
            listOf(
                phase.toString(), date.toString(), targetGid.value.toString(),
                deadlineUs.toString(), (deadlineUs + 3_000_000L).toString(), keys.channel(targetGid), "0",
            ),
        )
        assertThat(reply).hasSize(2)
        DeadlineOutcome(DeadlineCode.valueOf(reply[0] as String), reply[1] as Long)
    }

    /** 08:00:00 ≤ t < 08:00:03. */
    private fun phase1() = run(1, redisTimeUs() - 500_000L)

    /** t ≥ 08:00:03. */
    private fun phase2() = run(2, redisTimeUs() - 3_500_000L)

    /** `phase=1` 인데 08:00:03 뒤. */
    private fun phase1Late() = run(1, redisTimeUs() - 4_000_000L)

    // ---- 관찰 ----

    private fun hash(): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(group).collectList().awaitSingle().associate { it.key to it.value }
    }

    /** 이 그룹의 Stream 이벤트(버킷 Stream 을 다른 테스트 그룹과 함께 쓰므로 gid 로 거른다). */
    private fun events(): List<Map<String, String>> = runBlocking {
        redis.opsForStream<String, String>().range(keys.events(gid), Range.unbounded()).collectList().awaitSingle()
            .map { it.value }
            .filter { it["gid"] == gid.value.toString() }
    }

    private fun events(type: String) = events().filter { it["type"] == type }

    /** 발행이 도착할 시간을 조금 기다린 뒤 채널 메시지. */
    private fun channel(expectAtLeast: Int = 0): List<Map<String, Any?>> {
        val until = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (channelMessages.size < expectAtLeast && System.nanoTime() < until) Thread.sleep(5)
        Thread.sleep(50) // 더 올 메시지가 없는지
        return channelMessages.toList()
    }

    private fun ranking(raw: String?): List<Map<String, Any?>> = json.readValue(requireNotNull(raw) { "ranking 없음" })

    private fun ranks(raw: String?): List<Pair<String, Int>> = ranking(raw).map { it["u"] as String to (it["r"] as Number).toInt() }

    private fun statuses(h: Map<String, String>, vararg uids: String) = uids.map { h["$it:st"] }

    // ---- 시나리오 ----

    private val s1 = listOf(
        M("1", "a", "IN_PROGRESS", 1, cur = "q2", ls = ls("07:10:00")),
        M("2", "b", "NOT_STARTED", -1),
        M("3", "c", "SUCCEEDED", 3, ls = ls("07:30:00")),
    )

    private val s3 = listOf(
        M("1", "a", "IN_PROGRESS", 2, cur = "q3", ls = ls("07:40:00")),
        M("2", "b", "SUCCEEDED", 3, ls = ls("07:30:00")),
    )

    @Test
    fun `O5 1차 미룸 대상 없는 그룹 확정 (S1)`() {
        setUp(s1)

        val out = phase1()

        val h = hash()
        assertThat(out).isEqualTo(DeadlineOutcome(DeadlineCode.DECIDED, 1))
        assertThat(h["result"]).isEqualTo("FAILED_DEADLINE")
        assertThat(statuses(h, "1", "2", "3")).containsExactly("FAILED", "FAILED", "SUCCEEDED")
        assertThat(h.filterKeys { it.endsWith(":st") }.values).doesNotContain("NOT_STARTED", "IN_PROGRESS")
        assertThat(h["rankingFinal"]).isEqualTo("0")
        assertThat(h["version"]).isEqualTo("1")
        assertThat(h).doesNotContainKey("pending")
        assertThat(h["decidedAt"]).matches("\\d{16}")
        assertThat(ranks(h["ranking"])).containsExactly("c" to 1, "a" to 2, "b" to 3)
        // 상태를 FAILED 로 바꾼 뒤에 읽은 랭킹(ORACLE O31)
        assertThat(ranking(h["ranking"]).associate { it["u"] to it["s"] }).isEqualTo(mapOf("c" to "SUCCEEDED", "a" to "FAILED", "b" to "FAILED"))

        val decided = events("group.decided")
        assertThat(events()).hasSize(1)
        assertThat(decided).hasSize(1)
        decided.single().let { e ->
            assertThat(e).containsEntry("date", date.toString()).containsEntry("uid", "").containsEntry("lid", "")
                .containsEntry("version", "1").containsEntry("result", "FAILED_DEADLINE").containsEntry("rankingFinal", "0")
                .containsEntry("memberCount", "3").containsEntry("decidedAt", h["decidedAt"]).containsEntry("at", h["decidedAt"])
            assertThat(ranking(e["ranking"])).isEqualTo(ranking(h["ranking"]))
        }
        val messages = channel(1)
        assertThat(messages).hasSize(1)
        messages.single().let { msg ->
            assertThat(msg["type"]).isEqualTo("group.decided")
            assertThat(msg["version"]).isEqualTo(1)
            assertThat(msg["streamId"]).isNotNull()
            assertThat(msg["uid"]).isNull()
            assertThat(msg["gid"]).isEqualTo(gid.value.toString())
            assertThat(msg["date"]).isEqualTo(date.toString())
            assertThat(msg["at"]).isEqualTo(h["decidedAt"])
            assertThat(msg["hash"]).isEqualTo(h)
        }
    }

    @Test
    fun `O6 1차 3번 진행 중 그룹 미룸 (S3)`() {
        setUp(s3)
        val before = hash()

        val out = phase1()

        val h = hash()
        assertThat(out).isEqualTo(DeadlineOutcome(DeadlineCode.PENDING, 1))
        assertThat(h["pending"]).isEqualTo("1")
        assertThat(h).doesNotContainKeys("result", "decidedAt", "ranking", "rankingFinal")
        assertThat(h - "pending" - "version").isEqualTo(before - "version")
        assertThat(h["version"]).isEqualTo("1")
        assertThat(events()).isEmpty()
        val messages = channel(1)
        assertThat(messages).hasSize(1)
        assertThat(messages.single()["type"]).isEqualTo("group.pending")
        assertThat(messages.single()).containsKey("streamId")
        assertThat(messages.single()["streamId"]).isNull()
        assertThat(messages.single()["version"]).isEqualTo(1)
    }

    @Test
    fun `O7 미룸 경계 — 3번 진입 안 함 (S6)`() {
        setUp(listOf(M("1", "a", "IN_PROGRESS", 2, ls = ls("07:40:00")), M("2", "b", "SUCCEEDED", 3, ls = ls("07:30:00"))))

        assertThat(phase1().code).isEqualTo(DeadlineCode.DECIDED)
        assertThat(hash()["1:st"]).isEqualTo("FAILED")
    }

    @Test
    fun `O7 미룸 경계 — 부정 실패는 미룸 대상 아님 (S7)`() {
        setUp(listOf(
            M("1", "a", "CHEAT_FAILED", 2, cur = "q3", ls = ls("07:40:00")),
            M("2", "b", "IN_PROGRESS", 1, ls = ls("07:10:00")),
        ))

        assertThat(phase1().code).isEqualTo(DeadlineCode.DECIDED)
        assertThat(statuses(hash(), "1", "2")).containsExactly("CHEAT_FAILED", "FAILED")
    }

    @Test
    fun `O7 미룸 경계 — 진행도 1 이고 cur 있음`() {
        setUp(listOf(M("1", "a", "IN_PROGRESS", 1, cur = "q2", ls = ls("07:10:00")), M("2", "b", "SUCCEEDED", 3, ls = ls("07:30:00"))))

        assertThat(phase1().code).isEqualTo(DeadlineCode.DECIDED)
    }

    /** §1.1 G1 의 1차 전 상태 — `gus0` · `ivy0` IN_PROGRESS(cur 없음) · `hal0` 진행도 1 · `cur` 2번. */
    private fun g1BeforeDeadline(): List<M> =
        RankingCases.groups.single { it.id == "G1" }.members.mapIndexed { i, m ->
            val k = RankingCases.key(m)
            val base = M("u$i", m.loginId, m.status, m.progress, ls = k.lastSolvedAtUs, pn = k.passNo, late = k.late)
            when (m.loginId) {
                "gus0", "ivy0" -> base.copy(st = "IN_PROGRESS")
                "hal0" -> base.copy(st = "IN_PROGRESS", p = 1, cur = "q2", ls = ls("07:10:00"))
                else -> base
            }
        }

    @Test
    fun `O8 유예 반영 뒤 2차 랭킹 확정 (S2)`() {
        val members = g1BeforeDeadline()
        setUp(members)

        val first = phase1()
        val afterFirst = hash()
        assertThat(first.code).isEqualTo(DeadlineCode.DECIDED) // hal0 은 3번 진입이 아니므로 미루지 않음
        assertThat(afterFirst["result"]).isEqualTo("FAILED_DEADLINE")
        assertThat(afterFirst["rankingFinal"]).isEqualTo("0")
        val hal = members.single { it.lid == "hal0" }.uid
        assertThat(ranking(afterFirst["ranking"]).single { it["u"] == "hal0" }["p"]).isEqualTo(1)

        // hal0 의 2번 유예 풀이(도착 08:00:01.5 · 클릭 07:59:59.9) — solve-signal Lua `solve` 가 쓰는 필드
        runBlocking {
            redis.opsForHash<String, String>().putAll(group, mapOf("$hal:p" to "2", "$hal:ls" to ls("08:00:01.5").toString())).awaitSingle()
            redis.opsForHash<String, String>().remove(group, "$hal:cur").awaitSingle()
            redis.opsForHash<String, String>().increment(group, "version", 1).awaitSingle()
        }

        val second = phase2()
        val h = hash()
        assertThat(second).isEqualTo(DeadlineOutcome(DeadlineCode.FINALIZED, 3))
        assertThat(h["result"]).isEqualTo("FAILED_DEADLINE")
        assertThat(h["decidedAt"]).isEqualTo(afterFirst["decidedAt"])
        assertThat(h["rankingFinal"]).isEqualTo("1")
        val expected = RankingCases.groups.single { it.id == "G1" }.members.sortedBy { it.expectedOrder }
        assertThat(ranks(h["ranking"])).containsExactlyElementsOf(expected.map { it.loginId to it.expectedRank })
        assertThat(ranking(h["ranking"]).single { it["u"] == "hal0" }["p"]).isEqualTo(2)

        assertThat(events("group.decided")).hasSize(1)
        val final = events("group.ranking_final")
        assertThat(final).hasSize(1)
        final.single().let { e ->
            assertThat(e).containsEntry("result", "FAILED_DEADLINE").containsEntry("decidedAt", afterFirst["decidedAt"])
                .containsEntry("rankingFinal", "1").containsEntry("version", "3").containsEntry("uid", "").containsEntry("lid", "")
            assertThat(ranking(e["ranking"])).isEqualTo(ranking(h["ranking"]))
        }
        val messages = channel(2)
        assertThat(messages.map { it["type"] }).containsExactly("group.decided", "group.ranking_final")
        assertThat(messages.last()["streamId"]).isNotNull()
        assertThat(messages.last()["version"]).isEqualTo(3)
    }

    @Test
    fun `O9 2차 미룬 그룹 확정 (S5)`() {
        setUp(s3)
        assertThat(phase1().code).isEqualTo(DeadlineCode.PENDING)

        val out = phase2()

        val h = hash()
        assertThat(out).isEqualTo(DeadlineOutcome(DeadlineCode.DECIDED_FINAL, 2))
        assertThat(h["result"]).isEqualTo("FAILED_DEADLINE")
        assertThat(statuses(h, "1", "2")).containsExactly("FAILED", "SUCCEEDED")
        assertThat(h["rankingFinal"]).isEqualTo("1")
        assertThat(h).doesNotContainKey("pending")
        assertThat(ranks(h["ranking"])).containsExactly("b" to 1, "a" to 2)
        val decided = events("group.decided")
        assertThat(decided).hasSize(1)
        assertThat(decided.single()["rankingFinal"]).isEqualTo("1")
        assertThat(events("group.ranking_final")).isEmpty()
        assertThat(channel(2).map { it["type"] }).containsExactly("group.pending", "group.decided")
    }

    @Test
    fun `O10 미룬 그룹이 유예 안에 성공 (S4)`() {
        setUp(s3)
        assertThat(phase1().code).isEqualTo(DeadlineCode.PENDING)
        val before = hash()
        // a 의 3번 유예 풀이 → 전원 SUCCEEDED — solve-signal 이 result 를 확정하고 pending 을 지운다
        runBlocking {
            redis.opsForHash<String, String>().putAll(group, mapOf(
                "1:p" to "3", "1:st" to "SUCCEEDED", "1:ls" to ls("08:00:01.5").toString(),
                "result" to "SUCCEEDED", "decidedAt" to ls("08:00:01.5").toString(), "rankingFinal" to "0",
            )).awaitSingle()
            redis.opsForHash<String, String>().remove(group, "1:cur", "pending").awaitSingle()
            redis.opsForHash<String, String>().increment(group, "version", 1).awaitSingle()
        }

        val out = phase2()

        val h = hash()
        assertThat(out).isEqualTo(DeadlineOutcome(DeadlineCode.FINALIZED, 3))
        assertThat(h["result"]).isEqualTo("SUCCEEDED")
        assertThat(h["decidedAt"]).isEqualTo(ls("08:00:01.5").toString())
        assertThat(h["rankingFinal"]).isEqualTo("1")
        assertThat(h).doesNotContainKey("pending")
        assertThat(ranks(h["ranking"])).containsExactly("b" to 1, "a" to 2)
        assertThat(before["version"]).isEqualTo("1")
        assertThat(events("group.ranking_final")).hasSize(1)
        assertThat(events("group.decided")).isEmpty() // 결과는 solve-signal 이 냈다(여기서는 흉내만)
    }

    @Test
    fun `O11 08시 00분 03초 뒤 처리는 한 번에 (S8)`() {
        setUp(s3)

        val out = phase1Late()

        val h = hash()
        assertThat(out).isEqualTo(DeadlineOutcome(DeadlineCode.DECIDED_FINAL, 1))
        assertThat(h["rankingFinal"]).isEqualTo("1")
        assertThat(h["result"]).isEqualTo("FAILED_DEADLINE")
        assertThat(h).doesNotContainKey("pending")
        assertThat(channel(1).map { it["type"] }).containsExactly("group.decided")
        assertThat(events().map { it["type"] }).containsExactly("group.decided")
    }

    @Test
    fun `O12 시각 전 실행은 아무것도 안 함 (S10)`() {
        setUp(s1)
        val before = hash()

        assertThat(run(1, redisTimeUs() + 10_000_000L)).isEqualTo(DeadlineOutcome(DeadlineCode.EARLY, 0))
        // 2차: G = TIME + 1초(D = TIME − 2초) — 1차 구간이지만 2차는 아직
        assertThat(run(2, redisTimeUs() - 2_000_000L)).isEqualTo(DeadlineOutcome(DeadlineCode.EARLY, 0))

        assertThat(hash()).isEqualTo(before)
        assertThat(events()).isEmpty()
        assertThat(channel()).isEmpty()
    }

    @Test
    fun `O13 같은 단계 재실행 멱등 (S11 — S1 1차)`() {
        setUp(s1)
        val first = phase1()
        val snapshot = hash()

        repeat(2) { assertThat(phase1()).isEqualTo(DeadlineOutcome(DeadlineCode.SKIPPED, first.version)) }

        assertThat(hash()).isEqualTo(snapshot)
        assertThat(events()).hasSize(1)
        assertThat(channel(1)).hasSize(1)
    }

    @Test
    fun `O13 같은 단계 재실행 멱등 (S11 — S3 1차는 PENDING 그대로)`() {
        setUp(s3)
        val first = phase1()
        val snapshot = hash()

        repeat(2) { assertThat(phase1()).isEqualTo(DeadlineOutcome(DeadlineCode.PENDING, first.version)) }

        assertThat(hash()).isEqualTo(snapshot)
        assertThat(events()).isEmpty()
        assertThat(channel(1)).hasSize(1)
    }

    @Test
    fun `O13 같은 단계 재실행 멱등 (S2 의 1차 · 2차)`() {
        setUp(g1BeforeDeadline())
        assertThat(phase1().code).isEqualTo(DeadlineCode.DECIDED)
        repeat(2) { assertThat(phase1().code).isEqualTo(DeadlineCode.SKIPPED) }
        assertThat(hash()["version"]).isEqualTo("1")

        assertThat(phase2().code).isEqualTo(DeadlineCode.FINALIZED)
        val snapshot = hash()
        repeat(2) { assertThat(phase2()).isEqualTo(DeadlineOutcome(DeadlineCode.SKIPPED, 2)) }
        // 2차 뒤 1차가 늦게 돌아도(08:00:03 뒤) 아무것도 하지 않는다
        assertThat(phase1Late().code).isEqualTo(DeadlineCode.SKIPPED)

        assertThat(hash()).isEqualTo(snapshot)
        assertThat(events().map { it["type"] }).containsExactly("group.decided", "group.ranking_final")
        assertThat(channel(2)).hasSize(2)
    }

    private val allSucceeded = listOf(
        M("1", "a", "SUCCEEDED", 3, ls = ls("07:30:00")),
        M("2", "b", "SUCCEEDED", 3, ls = ls("07:40:00")),
    )

    private fun earlyResult(rankingFinal: String) = mapOf(
        "result" to "SUCCEEDED", "decidedAt" to ls("07:40:00").toString(), "rankingFinal" to rankingFinal,
        "ranking" to """[{"id":"1","u":"a","r":1,"p":3,"s":"SUCCEEDED","l":"${ls("07:30:00")}","n":null}]""",
    )

    @Test
    fun `O14 08시 전 확정 그룹 — rankingFinal 1 이면 2차도 SKIPPED (S12)`() {
        setUp(allSucceeded, earlyResult("1"))
        val before = hash()

        assertThat(phase1()).isEqualTo(DeadlineOutcome(DeadlineCode.SKIPPED, 0))
        assertThat(phase2()).isEqualTo(DeadlineOutcome(DeadlineCode.SKIPPED, 0))

        assertThat(hash()).isEqualTo(before)
        assertThat(events()).isEmpty()
        assertThat(channel()).isEmpty()
    }

    @Test
    fun `O14 08시 전 확정 그룹 — rankingFinal 0 이면 1차 SKIPPED · 2차 FINALIZED (S12)`() {
        setUp(allSucceeded, earlyResult("0"))
        val before = hash()

        assertThat(phase1()).isEqualTo(DeadlineOutcome(DeadlineCode.SKIPPED, 0))
        assertThat(hash()).isEqualTo(before)

        assertThat(phase2()).isEqualTo(DeadlineOutcome(DeadlineCode.FINALIZED, 1))
        val h = hash()
        assertThat(h["result"]).isEqualTo("SUCCEEDED")
        assertThat(h["decidedAt"]).isEqualTo(before["decidedAt"])
        assertThat(h["rankingFinal"]).isEqualTo("1")
        assertThat(ranks(h["ranking"])).containsExactly("a" to 1, "b" to 2)
        assertThat(events().map { it["type"] }).containsExactly("group.ranking_final")
    }

    @Test
    fun `O15 그룹 Hash 없음`() = runBlocking {
        val missing = GroupId(gid.value + 1)

        assertThat(run(1, redisTimeUs() - 500_000L, missing)).isEqualTo(DeadlineOutcome(DeadlineCode.NO_GROUP, 0))
        assertThat(run(2, redisTimeUs() - 3_500_000L, missing)).isEqualTo(DeadlineOutcome(DeadlineCode.NO_GROUP, 0))

        assertThat(redis.hasKey(keys.group(missing, date)).awaitSingle()).isFalse()
        assertThat(redis.hasKey(keys.members(missing, date)).awaitSingle()).isFalse()
        Unit
    }

    @Test
    fun `runDeadline 은 GameProperties 의 08시 경계와 clock-offset 으로 Lua 를 부른다`() {
        setUp(s3)
        // 지금 Redis 시각이 그날 08:00:00.5 가 되도록 시각을 옮긴다(game.clock-offset)
        fun repoAt(time: String): RedisGroupDayRepository {
            val offsetUs = ls(time) - redisTimeUs()
            return RedisGroupDayRepository(scripts, keys, GameProperties(clockOffset = Duration.ofNanos(offsetUs * 1_000L)))
        }

        assertThat(runBlocking { repoAt("07:59:59").runDeadline(gid, date, DeadlinePhase.FIRST) }.code).isEqualTo(DeadlineCode.EARLY)
        assertThat(runBlocking { repoAt("08:00:00.5").runDeadline(gid, date, DeadlinePhase.SECOND) }.code).isEqualTo(DeadlineCode.EARLY)
        assertThat(runBlocking { repoAt("08:00:00.5").runDeadline(gid, date, DeadlinePhase.FIRST) })
            .isEqualTo(DeadlineOutcome(DeadlineCode.PENDING, 1))
        assertThat(runBlocking { repoAt("08:00:04").runDeadline(gid, date, DeadlinePhase.SECOND) })
            .isEqualTo(DeadlineOutcome(DeadlineCode.DECIDED_FINAL, 2))
    }
}
