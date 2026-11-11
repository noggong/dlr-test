package com.delightroom.mission.infra.redis.gamesession

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
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
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * `start.lua` · [RedisProgressRepository.startMission](LLD game-session §4.2) — 지각 판정(O6)과, 문제은행 API 기준
 * (O5 · O7 ~ O11 · I6)이 기대는 Lua 쪽 반영 · 판정 순서를 받친다.
 *
 * 시각: Lua 의 `t` 를 목표 시각에 맞추려고 부르기 직전의 Redis `TIME` 을 읽어 `game.clock-offset = 목표 − TIME` 으로 넘긴다.
 * 명단은 [RedisRosterRepository] 로 만든다(앱 시각 D 07:10 — 만료 계산용).
 */
class StartScriptTest {
    private val redis = RedisTestContainer.template
    private val scripts = RedisScripts(redis)
    private val properties = GameProperties()
    private val keys = RedisKeys(properties)
    private val rosters = RedisRosterRepository(
        redis, scripts, keys, GameClock(properties, MutableClock(at(LocalTime.of(7, 10)), properties.zone)),
    )
    private val json = JsonMapper.builder().build()

    /** [withRoster] 가 false 면 명단을 만들지 않는다(`NO_ROSTER` 판정). */
    private inner class Group(vararg members: Pair<Long, String>, withRoster: Boolean = true) {
        val gid = GroupId(GID.incrementAndGet())
        val groupKey = keys.group(gid, D)
        val membersKey = keys.members(gid, D)
        val stream = keys.events(gid)
        val channel = keys.channel(gid)

        init {
            if (withRoster) runBlocking { rosters.createIfAbsent(DayRoster(gid, D, GroupName("시작"), members.associate { (u, l) -> UserId(u) to LoginId(l) })) }
        }

        fun hash(): Map<String, String> = runBlocking {
            redis.opsForHash<String, String>().entries(groupKey).collectMap({ it.key }, { it.value }).awaitSingle()
        }

        /** 이 그룹의 Stream 메시지(버킷 Stream 에서 gid 로 거른다) — (id, 필드), 오래된 것부터. */
        fun records(): List<Pair<String, Map<String, String>>> = runBlocking {
            redis.opsForStream<String, String>().range(stream, Range.unbounded()).collectList().awaitSingle()
                .map { r -> r.id.value to r.value.entries.associate { it.key.toString() to it.value.toString() } }
                .filter { it.second["gid"] == gid.value.toString() }
        }

        fun events(): List<Map<String, String>> = records().map { it.second }

        /** 게임 시각 [time] 에 `startMission` — Lua 의 `t` 가 그 시각(± 왕복)이 되게 오프셋을 맞춘다. */
        fun start(uid: Long, time: LocalTime): MissionStartOutcome = runBlocking {
            val offsetUs = micros(at(time)) - redisNowUs()
            val props = GameProperties(clockOffset = Duration.ofNanos(offsetUs * 1_000L))
            val clock = MutableClock(at(time), props.zone)
            RedisProgressRepository(redis, scripts, keys, GameClock(props, clock), props).startMission(UserId(uid), gid, D)
        }

        /** ARGV 를 직접 — 경계 µs 를 Redis `TIME` 기준으로 둔다(오프셋 0). */
        fun raw(uid: Long, startUs: Long, lateUs: Long, deadlineUs: Long): List<Any> = runBlocking {
            scripts.eval(
                "start",
                listOf(groupKey, membersKey, stream),
                listOf(uid.toString(), "$startUs", "$lateUs", "$deadlineUs", "0", channel, D.toString(), gid.value.toString()),
            )
        }
    }

    private suspend fun redisNowUs(): Long = scripts.eval("platform_now_probe", emptyList(), listOf("0"))[1] as Long

    private fun threeMembers() = Group(11L to "alice_a", 12L to "bob_b", 13L to "carol_c")

    @Test
    fun `O5 (바탕) 처음 시작 — p 0 · IN_PROGRESS · sa · late 0 · version 1 · mission_started 1건 · 채널 메시지 1건`(): Unit = runBlocking {
        val g = threeMembers()
        val outcome = ChannelProbe(g.channel).use { probe ->
            val o = g.start(11, LocalTime.of(7, 0, 1))
            val messages = probe.settle(1)
            assertThat(messages).hasSize(1)
            @Suppress("UNCHECKED_CAST")
            val msg = json.readValue(messages.single(), Map::class.java) as Map<String, Any?>
            assertThat(msg["type"]).isEqualTo("mission.started")
            assertThat(msg["uid"]).isEqualTo("11")
            assertThat(msg["gid"]).isEqualTo(g.gid.value.toString())
            assertThat(msg["version"]).isEqualTo(1)
            assertThat(msg["streamId"]).isEqualTo(g.records().single().first)
            @Suppress("UNCHECKED_CAST")
            assertThat((msg["hash"] as Map<String, String>)["11:st"]).isEqualTo("IN_PROGRESS")
            o
        }

        assertThat(outcome).isInstanceOf(MissionStartOutcome.Started::class.java)
        val started = outcome as MissionStartOutcome.Started
        assertThat(started.created).isTrue()
        assertThat(Duration.between(at(LocalTime.of(7, 0, 1)), started.startedAt).abs()).isLessThan(Duration.ofSeconds(1))
        val h = g.hash()
        val sa = micros(started.startedAt).toString()
        assertThat(h).containsEntry("11:p", "0").containsEntry("11:st", "IN_PROGRESS").containsEntry("11:sa", sa)
            .containsEntry("11:late", "0").containsEntry("version", "1")
        // 다른 그룹원은 그대로
        assertThat(h).containsEntry("12:p", "-1").containsEntry("12:st", "NOT_STARTED").doesNotContainKey("12:sa")
        val events = g.events()
        assertThat(events).hasSize(1)
        assertThat(events.single()).isEqualTo(
            mapOf(
                "type" to "mission.started", "date" to D.toString(), "gid" to g.gid.value.toString(), "uid" to "11", "lid" to "alice_a",
                "version" to "1", "at" to sa, "startedAt" to sa, "late" to "0",
            ),
        )
    }

    @Test
    fun `O6 지각 판정 — lateUs 가 TIME + 1초면 late 0, TIME − 1초면 late 1 · 비교식은 t 초과`(): Unit = runBlocking {
        val g = threeMembers()
        val now = redisNowUs()
        val hour = 3_600_000_000L

        assertThat(g.raw(11, now - hour, now + 1_000_000, now + hour)[0]).isEqualTo("OK")
        assertThat(g.raw(12, now - hour, now - 1_000_000, now + hour)[0]).isEqualTo("OK")

        assertThat(g.hash()).containsEntry("11:late", "0").containsEntry("12:late", "1")
        assertThat(g.events().map { it["uid"] to it["late"] }).containsExactly("11" to "0", "12" to "1")
        // 비교식 리뷰(ORACLE O6 — 정확히 07:00:03.000000 은 지각 아님): t > lateUs 만 지각
        val source = javaClass.classLoader.getResource("lua/start.lua")!!.readText()
        assertThat(source).contains("local late = (t > lateUs) and '1' or '0'")
    }

    @Test
    fun `O6 (바탕) 앱 경계 시각으로 — 07시 00분 02초 시작은 지각 아님 · 07시 00분 05초 시작은 지각`(): Unit = runBlocking {
        val g = threeMembers()

        g.start(11, LocalTime.of(7, 0, 2))
        g.start(12, LocalTime.of(7, 0, 5))

        assertThat(g.hash()).containsEntry("11:late", "0").containsEntry("12:late", "1")
    }

    @Test
    fun `O7 (바탕) 두 번째 호출은 아무것도 바꾸지 않고 같은 sa · created false · 이벤트 그대로 1건`(): Unit = runBlocking {
        val g = threeMembers()
        val first = g.start(11, LocalTime.of(7, 0, 1)) as MissionStartOutcome.Started
        val before = g.hash()

        val second = g.start(11, LocalTime.of(7, 30)) as MissionStartOutcome.Started

        assertThat(second.created).isFalse()
        assertThat(second.startedAt).isEqualTo(first.startedAt)
        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).hasSize(1)
    }

    @Test
    fun `O8 · O9 (바탕) 시간 창 — 07시 전은 GAME_NOT_OPEN, 08시부터는 SUBMISSION_CLOSED · 아무것도 바꾸지 않는다`(): Unit = runBlocking {
        val g = threeMembers()
        val before = g.hash()

        assertThat(g.start(11, LocalTime.of(6, 59, 59))).isEqualTo(MissionStartOutcome.Rejected(ErrorCode.GAME_NOT_OPEN))
        assertThat(g.start(11, LocalTime.of(8, 0, 0))).isEqualTo(MissionStartOutcome.Rejected(ErrorCode.SUBMISSION_CLOSED))
        assertThat(g.start(11, LocalTime.of(8, 0, 2))).isEqualTo(MissionStartOutcome.Rejected(ErrorCode.SUBMISSION_CLOSED))
        // µs 경계 — Redis TIME 바로 위 · 아래로 startUs · deadlineUs 를 둔다
        val now = redisNowUs()
        assertThat(g.raw(11, now + 1_000_000, now + 2_000_000, now + 3_000_000)).containsExactly("ERR", "GAME_NOT_OPEN")
        assertThat(g.raw(11, now - 2_000_000, now - 1_000_000, now)).containsExactly("ERR", "SUBMISSION_CLOSED")

        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `O8 (바탕) 판정 순서 — 명단이 없어도 07시 전이면 NO_ROSTER 가 아니라 GAME_NOT_OPEN, 창 안이면 NO_ROSTER`(): Unit = runBlocking {
        val g = Group(withRoster = false)

        assertThat(g.start(11, LocalTime.of(6, 59))).isEqualTo(MissionStartOutcome.Rejected(ErrorCode.GAME_NOT_OPEN))
        assertThat(g.start(11, LocalTime.of(7, 10))).isEqualTo(MissionStartOutcome.NoRoster)
        assertThat(redis.hasKey(g.groupKey).awaitSingle()).isFalse()
    }

    @Test
    fun `O10 (바탕) 명단에 없는 유저는 NOT_PARTICIPANT · 그룹 Hash 에 필드가 생기지 않는다`(): Unit = runBlocking {
        val g = threeMembers()
        val before = g.hash()

        assertThat(g.start(99, LocalTime.of(7, 40))).isEqualTo(MissionStartOutcome.Rejected(ErrorCode.NOT_PARTICIPANT))

        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.hash().keys.filter { it.startsWith("99:") }).isEmpty()
    }

    @Test
    fun `O11 (바탕) 1명 그룹은 SOLO_GROUP — 그룹 Hash 를 만들지 않는다`(): Unit = runBlocking {
        val g = Group(21L to "solo_s")

        assertThat(g.start(21, LocalTime.of(7, 10))).isEqualTo(MissionStartOutcome.Rejected(ErrorCode.SOLO_GROUP))

        assertThat(redis.hasKey(g.groupKey).awaitSingle()).isFalse()
        assertThat(g.events()).isEmpty()
    }

    @Test
    fun `I6 (바탕) 진행도 2 인 유저 — p · sa 그대로, mission_started 0건, 완료한 유저도 OK`(): Unit = runBlocking {
        val g = threeMembers()
        val sa = micros(at(LocalTime.of(7, 0, 4))).toString()
        redis.opsForHash<String, String>().putAll(
            g.groupKey,
            mapOf("11:p" to "2", "11:st" to "IN_PROGRESS", "11:sa" to sa, "12:p" to "3", "12:st" to "SUCCEEDED", "12:sa" to sa),
        ).awaitSingle()
        val before = g.hash()

        val a = g.start(11, LocalTime.of(7, 20)) as MissionStartOutcome.Started
        val b = g.start(12, LocalTime.of(7, 20)) as MissionStartOutcome.Started

        assertThat(a.created).isFalse()
        assertThat(a.startedAt).isEqualTo(microsToInstant(sa.toLong()))
        assertThat(b.created).isFalse()
        assertThat(g.hash()).isEqualTo(before)
        assertThat(g.events()).isEmpty()
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val GID = AtomicLong(Math.floorMod(System.nanoTime(), 1_000_000L) * 1_000L + 7_000_000_000L)

        fun at(time: LocalTime) = ZonedDateTime.of(D, time, GameProperties().zone).toInstant()

        fun micros(i: java.time.Instant): Long = i.epochSecond * 1_000_000L + i.nano / 1_000L
    }
}
