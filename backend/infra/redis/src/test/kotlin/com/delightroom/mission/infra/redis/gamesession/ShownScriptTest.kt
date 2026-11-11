package com.delightroom.mission.infra.redis.gamesession

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisProgressRepository
import com.delightroom.mission.infra.redis.RedisRosterRepository
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import com.delightroom.mission.infra.redis.signal.SignalScriptSupport.ChannelProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * `shown.lua` · [RedisProgressRepository.markShown](LLD game-session §4.3) — 진입 신호 API 기준(O17 ~ O24 · I3)이 기대는
 * Lua 쪽 고정 · 멱등 · 판정 순서와, API 테스트가 못 보는 µs 시간 창 경계를 받친다.
 *
 * 시각: [StartScriptTest] 와 같이 부르기 직전의 Redis `TIME` 을 읽어 `game.clock-offset = 목표 − TIME` 으로 넘긴다.
 * 그룹원 상태는 명단(roster)으로 만든 그룹 Hash 에 직접 적는다 — `start` 의 반영은 [StartScriptTest] 가 본다.
 */
class ShownScriptTest {
    private val redis = RedisTestContainer.template
    private val scripts = RedisScripts(redis)
    private val properties = GameProperties()
    private val keys = RedisKeys(properties)
    private val rosters = RedisRosterRepository(
        redis, scripts, keys, GameClock(properties, MutableClock(at(LocalTime.of(7, 10)), properties.zone)),
    )

    private inner class Group(vararg members: Pair<Long, String>, withRoster: Boolean = true) {
        val gid = GroupId(GID.incrementAndGet())
        val groupKey = keys.group(gid, D)
        val stream = keys.events(gid)

        init {
            if (withRoster) runBlocking { rosters.createIfAbsent(DayRoster(gid, D, GroupName("진입"), members.associate { (u, l) -> UserId(u) to LoginId(l) })) }
        }

        fun hash(): Map<String, String> = runBlocking {
            redis.opsForHash<String, String>().entries(groupKey).collectMap({ it.key }, { it.value }).awaitSingle()
        }

        fun set(vararg fields: Pair<String, String>) = runBlocking {
            redis.opsForHash<String, String>().putAll(groupKey, fields.toMap()).awaitSingleOrNull()
        }

        /** 시작한 그룹원 — 진행도 [p] · 상태 [st]. */
        fun started(uid: Long, p: Int = 0, st: String = "IN_PROGRESS") =
            set("$uid:p" to "$p", "$uid:st" to st, "$uid:sa" to micros(at(LocalTime.of(7, 0, 1))).toString(), "$uid:late" to "0")

        fun events(): List<Map<String, String>> = runBlocking {
            redis.opsForStream<String, String>().range(stream, Range.unbounded()).collectList().awaitSingle()
                .map { r -> r.value.entries.associate { it.key.toString() to it.value.toString() } }
                .filter { it["gid"] == gid.value.toString() }
        }

        /** 게임 시각 [time] 에 `markShown` — [qid] 가 null 이면 「없는 PK · 난이도 ≠ 번호」(qok 0). */
        fun shown(uid: Long, no: Int, pk: String, qid: Long?, time: LocalTime = LocalTime.of(7, 10)): ShownOutcome = runBlocking {
            val offsetUs = micros(at(time)) - redisNowUs()
            val props = GameProperties(clockOffset = Duration.ofNanos(offsetUs * 1_000L))
            RedisProgressRepository(redis, scripts, keys, GameClock(props, MutableClock(at(time), props.zone)), props)
                .markShown(UserId(uid), gid, D, no, QuestionId(pk), qid)
        }

        /** ARGV 를 직접 — 경계 µs 를 Redis `TIME` 기준으로 둔다(오프셋 0). */
        fun raw(uid: Long, startUs: Long, deadlineUs: Long): List<Any> = runBlocking {
            scripts.eval(
                "shown",
                listOf(groupKey, keys.members(gid, D), stream),
                listOf("$uid", "1", Q1, "101", "1", "$startUs", "$deadlineUs", "0", D.toString(), gid.value.toString()),
            )
        }
    }

    private suspend fun redisNowUs(): Long = scripts.eval("platform_now_probe", emptyList(), listOf("0"))[1] as Long

    private fun threeMembers() = Group(11L to "alice_a", 12L to "bob_b", 13L to "carol_c")

    private fun rejected(code: ErrorCode) = ShownOutcome.Rejected(code)

    @Test
    fun `O17 (바탕) 처음 진입 — cur 고정 · question_shown 1건(필드 정본) · version 그대로 · publish 없음`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(11)
        g.set("version" to "4")

        val outcome = ChannelProbe(keys.channel(g.gid)).use { probe ->
            val o = g.shown(11, 1, Q1, 101)
            assertThat(probe.settle(0)).isEmpty()
            o
        }

        assertThat(outcome).isEqualTo(ShownOutcome.Shown(0, MemberStatus.IN_PROGRESS, created = true))
        val h = g.hash()
        assertThat(h).containsEntry("11:cur", Q1).containsEntry("version", "4").containsEntry("11:p", "0")
        val e = g.events().single()
        val at = e.getValue("at")
        assertThat(Duration.between(at(LocalTime.of(7, 10)), microsToInstant(at.toLong())).abs()).isLessThan(Duration.ofSeconds(1))
        assertThat(e).isEqualTo(
            mapOf(
                "type" to "question.shown", "date" to D.toString(), "gid" to g.gid.value.toString(), "uid" to "11", "lid" to "alice_a",
                "version" to "4", "at" to at, "questionNo" to "1", "questionId" to Q1, "qid" to "101", "shownAt" to at,
            ),
        )
    }

    @Test
    fun `O18 (바탕) 같은 문제로 다시 — 아무것도 바꾸지 않고 created false · 이벤트 그대로 1건`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(11, p = 1)
        g.shown(11, 2, Q2, 201)
        val before = g.hash()

        val again = (1..2).map { g.shown(11, 2, Q2, 201, LocalTime.of(7, 30)) }

        assertThat(again).containsOnly(ShownOutcome.Shown(1, MemberStatus.IN_PROGRESS, created = false))
        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).hasSize(1)
    }

    @Test
    fun `O20 (바탕) 시간 창 — 07시 전 GAME_NOT_OPEN · 08시부터 SUBMISSION_CLOSED, µs 경계는 TIME 바로 위 · 아래`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(11)
        val before = g.hash()

        assertThat(g.shown(11, 1, Q1, 101, LocalTime.of(6, 59, 59))).isEqualTo(rejected(ErrorCode.GAME_NOT_OPEN))
        assertThat(g.shown(11, 1, Q1, 101, LocalTime.of(8, 0, 0))).isEqualTo(rejected(ErrorCode.SUBMISSION_CLOSED))
        val now = redisNowUs()
        assertThat(g.raw(11, now + 1_000_000, now + 2_000_000)).containsExactly("ERR", "GAME_NOT_OPEN")
        assertThat(g.raw(11, now - 2_000_000, now)).containsExactly("ERR", "SUBMISSION_CLOSED")

        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).isEmpty()
        // 비교식 리뷰 — startUs ≤ t < deadlineUs 만 통과
        val source = javaClass.classLoader.getResource("lua/shown.lua")!!.readText()
        assertThat(source).contains("if t < startUs then").contains("if t >= deadlineUs then")
    }

    @Test
    fun `O10 · O11 (바탕) 명단 없음 NO_ROSTER · 그룹원 아님 NOT_PARTICIPANT · 1명 그룹 SOLO_GROUP — 키가 생기지 않는다`(): Unit = runBlocking {
        val none = Group(withRoster = false)
        assertThat(none.shown(11, 1, Q1, 101)).isEqualTo(ShownOutcome.NoRoster)
        assertThat(redis.hasKey(none.groupKey).awaitSingle()).isFalse()

        val g = threeMembers()
        val before = g.hash()
        assertThat(g.shown(99, 1, Q1, 101)).isEqualTo(rejected(ErrorCode.NOT_PARTICIPANT))
        assertThat(g.hash()).isEqualTo(before)

        val solo = Group(21L to "solo_s")
        assertThat(solo.shown(21, 1, Q1, 101)).isEqualTo(rejected(ErrorCode.SOLO_GROUP))
        assertThat(redis.hasKey(solo.groupKey).awaitSingle()).isFalse()
        assertThat(g.events() + solo.events()).isEmpty()
    }

    @Test
    fun `O21 (바탕) 시작 전 MISSION_NOT_STARTED · SUCCEEDED · CHEAT_FAILED 는 MISSION_ALREADY_FINISHED`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(12, p = 3, st = "SUCCEEDED")
        g.started(13, p = 1, st = "CHEAT_FAILED")

        assertThat(g.shown(11, 1, Q1, 101)).isEqualTo(rejected(ErrorCode.MISSION_NOT_STARTED))
        assertThat(g.shown(12, 1, Q1, 101)).isEqualTo(rejected(ErrorCode.MISSION_ALREADY_FINISHED))
        assertThat(g.shown(13, 2, Q2, 201)).isEqualTo(rejected(ErrorCode.MISSION_ALREADY_FINISHED))
        assertThat(g.hash().keys.filter { it.endsWith(":cur") }).isEmpty()
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `O22 (바탕) QUESTION_MISMATCH — qok 0 · 그 번호에 다른 문제가 고정됨, cur 그대로`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(11)

        assertThat(g.shown(11, 1, Q1, null)).isEqualTo(rejected(ErrorCode.QUESTION_MISMATCH))
        assertThat(g.shown(11, 1, Q1, 101)).isInstanceOf(ShownOutcome.Shown::class.java)
        assertThat(g.shown(11, 1, Q1B, 102)).isEqualTo(rejected(ErrorCode.QUESTION_MISMATCH))

        assertThat(g.hash()).containsEntry("11:cur", Q1)
        assertThat(g.events()).hasSize(1)
    }

    @Test
    fun `O23 (바탕) QUESTION_OUT_OF_ORDER — 진행도 0 에 2번 · 진행도 1 에 1번(늦은 재시도)`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(11)
        g.started(12, p = 1)

        assertThat(g.shown(11, 2, Q2, 201)).isEqualTo(rejected(ErrorCode.QUESTION_OUT_OF_ORDER))
        assertThat(g.shown(12, 1, Q1, 101)).isEqualTo(rejected(ErrorCode.QUESTION_OUT_OF_ORDER))
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `O24 (바탕) 판정 순서 — 시작 전 + qok 0 은 MISSION_NOT_STARTED, 시작 뒤 + qok 0 + 3번은 QUESTION_MISMATCH`(): Unit = runBlocking {
        val g = threeMembers()
        g.started(12)

        assertThat(g.shown(11, 1, Q1, null)).isEqualTo(rejected(ErrorCode.MISSION_NOT_STARTED))
        assertThat(g.shown(12, 3, Q1, null)).isEqualTo(rejected(ErrorCode.QUESTION_MISMATCH))
    }

    @Test
    fun `I3 (바탕) 같은 번호 · 다른 PK 2건 동시 × 50회 — OK 정확히 1 · cur = 그 PK · question_shown 1건`(): Unit = runBlocking {
        repeat(50) { round ->
            val g = threeMembers()
            g.started(11)

            val outcomes = listOf(Q1 to 101L, Q1B to 102L)
                .map { (pk, qid) -> async(Dispatchers.IO) { pk to g.shown(11, 1, pk, qid) } }.awaitAll()

            val won = outcomes.filter { it.second is ShownOutcome.Shown }
            assertThat(won).describedAs("round $round").hasSize(1)
            assertThat(outcomes.map { it.second } - won.map { it.second }.toSet())
                .describedAs("round $round").containsExactly(rejected(ErrorCode.QUESTION_MISMATCH))
            assertThat(g.hash()["11:cur"]).describedAs("round $round").isEqualTo(won.single().first)
            assertThat(g.events()).describedAs("round $round").hasSize(1)
        }
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val GID = AtomicLong(Math.floorMod(System.nanoTime(), 1_000_000L) * 1_000L + 8_000_000_000L)
        val Q1 = "a".repeat(64)
        val Q1B = "b".repeat(64)
        val Q2 = "c".repeat(64)

        fun at(time: LocalTime): Instant = ZonedDateTime.of(D, time, GameProperties().zone).toInstant()

        fun micros(i: Instant): Long = i.epochSecond * 1_000_000L + i.nano / 1_000L

        fun microsToInstant(us: Long): Instant = Instant.ofEpochSecond(us / 1_000_000L, (us % 1_000_000L) * 1_000L)
    }
}
