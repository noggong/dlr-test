package com.delightroom.mission.infra.redis.gamesession

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisRosterRepository
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * `roster.lua` · [RedisRosterRepository](LLD game-session §4.1 · §2.2) — ORACLE O2(명단 확정 · `#size`)가 기대는 Redis 쪽.
 * 그룹 Hash · 그날 그룹 목록 · 만료와 「없을 때만」은 `worker: member` 기준(O27 · O28 · O30 · I4)의 판정 전에 여기서 받친다.
 */
class RosterScriptTest {
    private val redis = RedisTestContainer.template
    private val properties = GameProperties()
    private val keys = RedisKeys(properties)
    /** 앱 게임 시각 D 07:10 — Redis 실제 시각과 상관없이 TTL 은 이 시각부터 다음 날 08:00 까지다(LLD v5 §4.1). */
    private val clock = MutableClock(OffsetDateTime.parse("${D}T07:10:00+09:00").toInstant(), properties.zone)
    private val gameClock = GameClock(properties, clock)
    private val repository = RedisRosterRepository(redis, RedisScripts(redis), keys, gameClock)

    private fun roster(gid: GroupId, vararg members: Pair<Long, String>) =
        DayRoster(gid, D, GroupName("아침 수영"), members.associate { (uid, lid) -> UserId(uid) to LoginId(lid) })

    private fun newGid() = GroupId(GID.incrementAndGet())

    private suspend fun hash(key: String): Map<String, String> =
        redis.opsForHash<String, String>().entries(key).collectMap({ it.key }, { it.value }).awaitSingle()

    private suspend fun ttl(key: String): Duration = redis.getExpire(key).awaitSingle()

    private suspend fun isDayMember(gid: GroupId): Boolean =
        redis.opsForSet().isMember(keys.dayGroups(keys.bucket(gid), D), gid.value.toString()).awaitSingle()

    @Test
    fun `O2 (바탕) 2명 이상 그룹 — 명단 Hash · 그룹 Hash(-1 · NOT_STARTED · version 0) · 그날 그룹 목록 · 만료는 게임 시각으로 다음 날 08시`(): Unit = runBlocking {
        val gid = newGid()

        val created = repository.createIfAbsent(roster(gid, 11L to "alice_a", 12L to "bob_b", 13L to "carol_c"), null)

        assertThat(created).isTrue()
        assertThat(hash(keys.members(gid, D))).isEqualTo(
            mapOf("#size" to "3", "#name" to "아침 수영", "11" to "alice_a", "12" to "bob_b", "13" to "carol_c"),
        )
        val group = hash(keys.group(gid, D))
        assertThat(group).containsEntry("date", D.toString()).containsEntry("gid", gid.value.toString())
            .containsEntry("size", "3").containsEntry("version", "0")
        for ((uid, lid) in listOf("11" to "alice_a", "12" to "bob_b", "13" to "carol_c")) {
            assertThat(group).containsEntry("$uid:lid", lid).containsEntry("$uid:p", "-1").containsEntry("$uid:st", "NOT_STARTED")
        }
        assertThat(group).hasSize(4 + 3 * 3)
        assertThat(isDayMember(gid)).isTrue()
        // D 07:10 → D+1 08:00 = 24시간 50분(± 1초)
        val expected = Duration.ofHours(24).plusMinutes(50)
        for (key in listOf(keys.members(gid, D), keys.group(gid, D), keys.dayGroups(keys.bucket(gid), D))) {
            assertThat(ttl(key)).describedAs(key).isBetween(expected.minusSeconds(1), expected)
        }
    }

    @Test
    fun `O2 (바탕) 1명 · 0명 그룹은 명단 Hash 만 — 그룹 Hash 와 그날 그룹 목록에 없다`(): Unit = runBlocking {
        val solo = newGid()
        val zero = newGid()

        assertThat(repository.createIfAbsent(roster(solo, 21L to "solo_s"), null)).isTrue()
        assertThat(repository.createIfAbsent(roster(zero), null)).isTrue()

        assertThat(hash(keys.members(solo, D))).isEqualTo(mapOf("#size" to "1", "#name" to "아침 수영", "21" to "solo_s"))
        assertThat(hash(keys.members(zero, D))).isEqualTo(mapOf("#size" to "0", "#name" to "아침 수영"))
        for (gid in listOf(solo, zero)) {
            assertThat(redis.hasKey(keys.group(gid, D)).awaitSingle()).isFalse()
            assertThat(isDayMember(gid)).isFalse()
        }
        assertThat(repository.find(solo, D)!!.isSolo).isTrue()
        assertThat(repository.find(zero, D)!!.size).isZero()
    }

    @Test
    fun `O2 (바탕) 이미 있으면 아무것도 바꾸지 않고 created 0 — 다른 내용이 와도 처음 명단 그대로`(): Unit = runBlocking {
        val gid = newGid()
        repository.createIfAbsent(roster(gid, 31L to "alice_a", 32L to "bob_b"), null)
        redis.opsForHash<String, String>().put(keys.group(gid, D), "31:p", "0").awaitSingle()

        val again = repository.createIfAbsent(roster(gid, 31L to "alice_a", 32L to "bob_b", 33L to "late_d"), null)

        assertThat(again).isFalse()
        assertThat(hash(keys.members(gid, D))["#size"]).isEqualTo("2")
        assertThat(hash(keys.group(gid, D))).containsEntry("31:p", "0").doesNotContainKey("33:lid")
    }

    @Test
    fun `O2 (바탕) 그룹 Hash 가 먼저 있으면(재적재) 진행도를 -1 로 덮지 않는다`(): Unit = runBlocking {
        val gid = newGid()
        redis.opsForHash<String, String>().putAll(keys.group(gid, D), mapOf("version" to "4", "41:p" to "2", "41:st" to "IN_PROGRESS"))
            .awaitSingle()

        assertThat(repository.createIfAbsent(roster(gid, 41L to "alice_a", 42L to "bob_b"), null)).isTrue()

        assertThat(hash(keys.group(gid, D))).isEqualTo(mapOf("version" to "4", "41:p" to "2", "41:st" to "IN_PROGRESS"))
        assertThat(hash(keys.members(gid, D))["#size"]).isEqualTo("2")
        assertThat(isDayMember(gid)).isTrue()
    }

    @Test
    fun `O2 (바탕) 명단이 없으면 find 는 null · 있으면 그날 명단`(): Unit = runBlocking {
        val gid = newGid()
        assertThat(repository.find(gid, D)).isNull()

        repository.createIfAbsent(roster(gid, 51L to "bob_b", 52L to "alice_a"), null)

        val found = repository.find(gid, D)!!
        assertThat(found.name.value).isEqualTo("아침 수영")
        assertThat(found.members).isEqualTo(mapOf(UserId(51) to LoginId("bob_b"), UserId(52) to LoginId("alice_a")))
        assertThat(found.sortedLoginIds().map { it.value }).containsExactly("alice_a", "bob_b")
    }

    @Test
    fun `O2 (바탕) 같은 그룹 명단을 동시에 50번 만들어도 created 1 은 정확히 한 번`(): Unit = runBlocking {
        repeat(5) {
            val gid = newGid()
            val results = coroutineScope {
                List(50) { async { repository.createIfAbsent(roster(gid, 61L to "alice_a", 62L to "bob_b"), null) } }.awaitAll()
            }
            assertThat(results.count { it }).isEqualTo(1)
        }
    }

    @Test
    fun `O2 (바탕) 게임 시각으로 만료가 이미 지났으면 만들지 않고 실패한다`(): Unit = runBlocking {
        val gid = newGid()
        clock.set(OffsetDateTime.parse("${D.plusDays(1)}T08:00:00+09:00").toInstant())

        assertThatThrownBy { runBlocking { repository.createIfAbsent(roster(gid, 71L to "alice_a", 72L to "bob_b"), null) } }
            .hasMessageContaining("만료가 이미 지났다")
        assertThat(redis.hasKey(keys.members(gid, D)).awaitSingle()).isFalse()
    }

    @Test
    fun `O2 (바탕) roster lua 는 남은 ms 가 1 미만이면 스크립트 오류`(): Unit = runBlocking {
        val gid = newGid()
        val scripts = RedisScripts(redis)
        val ks = listOf(keys.members(gid, D), keys.group(gid, D), keys.dayGroups(keys.bucket(gid), D))

        assertThatThrownBy { runBlocking { scripts.eval("roster", ks, listOf(D.toString(), "${gid.value}", "g", "0", "0")) } }
            .hasStackTraceContaining("ttlMs must be positive")
        assertThat(redis.hasKey(keys.members(gid, D)).awaitSingle()).isFalse()
    }

    @Test
    fun `O22 재적재 꼬리 — 그룹 Hash 를 만들 때 덮을 칸 · version · 결과가 있으면 랭킹을 다시 계산`(): Unit = runBlocking {
        val gid = newGid()
        val restore = GroupRestore(
            version = 1_767_225_600_123,
            fields = linkedMapOf(
                "81:p" to "3", "81:st" to "SUCCEEDED", "81:sa" to "1767225000000001", "81:late" to "0", "81:ls" to "1767225300000002",
                "82:p" to "3", "82:st" to "SUCCEEDED", "82:sa" to "1767225000000003", "82:late" to "1", "82:ls" to "1767225200000004",
                "result" to "SUCCEEDED", "decidedAt" to "1767225300000002", "rankingFinal" to "1",
            ),
            rerank = true,
        )

        assertThat(repository.createIfAbsent(roster(gid, 81L to "alice_a", 82L to "bob_b"), restore)).isTrue()

        val group = hash(keys.group(gid, D))
        assertThat(group).containsAllEntriesOf(restore.fields).containsEntry("version", "1767225600123")
            .containsEntry("81:lid", "alice_a").containsEntry("82:lid", "bob_b")
        // 랭킹은 되살린 칸으로 다시 계산 — 82 가 먼저 풀었다
        assertThat(group.getValue("ranking")).contains("\"id\":\"82\"").contains("\"r\":1")
        assertThat(group.getValue("ranking").indexOf("\"id\":\"82\"")).isLessThan(group.getValue("ranking").indexOf("\"id\":\"81\""))
        assertThat(isDayMember(gid)).isTrue()

        // 이미 있으면 꼬리도 무시한다 — 늦게 읽은 기록이 먼저 선 그룹을 덮지 않는다
        val again = GroupRestore(1, mapOf("81:p" to "0"), rerank = false)
        assertThat(repository.createIfAbsent(roster(gid, 81L to "alice_a", 82L to "bob_b"), again)).isFalse()
        assertThat(hash(keys.group(gid, D))).isEqualTo(group)
    }

    @Test
    fun `O22 재적재 꼬리의 칸 이름 · 값이 규칙 밖이면 스크립트 오류이고 키가 하나도 생기지 않는다`(): Unit = runBlocking {
        for (field in listOf("81:lid", "99:p", "version", "pending", "ranking", "81:p:x", "date")) {
            val gid = newGid()
            val restore = GroupRestore(5, mapOf(field to "1"), rerank = false)

            assertThatThrownBy { runBlocking { repository.createIfAbsent(roster(gid, 81L to "alice_a", 82L to "bob_b"), restore) } }
                .describedAs(field).hasStackTraceContaining("rehydrate field not allowed")
            for (key in listOf(keys.members(gid, D), keys.group(gid, D))) {
                assertThat(redis.hasKey(key).awaitSingle()).describedAs("$field $key").isFalse()
            }
        }
        // 값 꼴도 쓰기 전에 거른다 — 랭킹 다시 계산이 쓰기 뒤에 실패해 반쪽 Hash 가 남지 않게
        val badValues = listOf(
            "81:p" to "4", "81:p" to "-2", "81:p" to "x", "81:st" to "DONE", "81:sa" to "1e3", "81:late" to "2",
            "81:ls" to "-1", "81:pn" to "0", "81:cur" to "XYZ", "result" to "NO_RESULT", "decidedAt" to "", "rankingFinal" to "true",
        )
        for ((field, value) in badValues) {
            val gid = newGid()
            val restore = GroupRestore(5, mapOf(field to value), rerank = false)

            assertThatThrownBy { runBlocking { repository.createIfAbsent(roster(gid, 81L to "alice_a", 82L to "bob_b"), restore) } }
                .describedAs("$field=$value").hasStackTraceContaining("rehydrate value not allowed")
            assertThat(redis.hasKey(keys.members(gid, D)).awaitSingle()).describedAs("$field=$value").isFalse()
        }
    }

    @Test
    fun `O25 (바탕) 버킷 표시 — 쓰면 다 있고, 하나를 지우면 하나가 빈다`(): Unit = runBlocking {
        val day = D.plusDays(3)
        assertThat(repository.countUnmarked(day)).isEqualTo(properties.bucketCount)

        repository.markConfirmed(day)
        assertThat(repository.countUnmarked(day)).isZero()
        // 만료는 명단과 같다(다음 날 08:00 — 게임 시각 D 07:10 에서 보면 3일 + 24시간 50분 근처)
        assertThat(ttl(keys.rehydrateMark(5, day))).isGreaterThan(Duration.ofDays(3))

        redis.delete(keys.rehydrateMark(5, day)).awaitSingle()
        assertThat(repository.countUnmarked(day)).isEqualTo(1)
        repository.markConfirmed(day)
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val GID = AtomicLong(System.nanoTime() % 1_000_000L * 1_000L)
    }
}
