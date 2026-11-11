package com.delightroom.mission.app.worker.rehydrate

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.infra.redis.RedisKeys
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.assertj.core.api.Assertions.assertThat
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 테스트 그룹 — 그룹원 uid 는 [uids] 순서(로그인 아이디 `<tag><i>`). */
data class TestGroup(val gid: GroupId, val uids: List<Long>)

/** 문제 하나 — `questions.id` · `pk_hash`. */
data class Q(val id: Long, val pk: String)

/**
 * 재적재 테스트 도우미(ORACLE platform O22 ~ O26). 스키마 · 문제 시드는 `spring.sql.init`(schema.sql · data.sql)이 적용한다.
 * 시험 날짜 [date] 는 KST 오늘 + 며칠 — `users.joined_at`(지금)이 그날 07:00 전이라 모두 그날 그룹원이다.
 */
class RehydrateFixture(
    private val db: DatabaseClient,
    private val redis: ReactiveStringRedisTemplate,
    private val keys: RedisKeys,
    val date: LocalDate,
) {
    private val json = JsonMapper.builder().build()

    suspend fun group(size: Int): TestGroup {
        partitions()
        val tag = "rh" + UUID.randomUUID().toString().take(8)
        val gid = db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, :n, :c) RETURNING id")
            .bind("n", tag).bind("c", size)
            .map { row -> row.get("id", Number::class.java)!!.toLong() }.one().awaitSingle()
        val uids = (1..size).map { i ->
            db.sql("INSERT INTO users (login_id, password_hash, group_id) VALUES (:l, 'x', :g) RETURNING id")
                .bind("l", "$tag$i").bind("g", gid)
                .map { row -> row.get("id", Number::class.java)!!.toLong() }.one().awaitSingle()
        }
        return TestGroup(GroupId(gid), uids)
    }

    /** 난이도 [no] 의 문제 [count] 개(id 순). */
    suspend fun questions(no: Int, count: Int = 3): List<Q> =
        db.sql("SELECT id, pk_hash FROM questions WHERE difficulty = :d ORDER BY id LIMIT :n")
            .bind("d", no).bind("n", count)
            .map { row -> Q(row.get("id", Number::class.java)!!.toLong(), row.get("pk_hash", String::class.java)!!) }
            .all().collectList().awaitSingle()

    /** [date] 의 하루 파티션 4개(이미 있으면 그대로). */
    suspend fun partitions(day: LocalDate = date) {
        val suffix = day.toString().replace("-", "")
        for (t in listOf("daily_progress", "solve_signals", "group_results", "group_result_members")) {
            db.sql("CREATE TABLE IF NOT EXISTS ${t}_$suffix PARTITION OF $t FOR VALUES FROM ('$day') TO ('${day.plusDays(1)}')")
                .then().awaitFirstOrNull()
        }
    }

    suspend fun hash(key: String): Map<String, String> =
        redis.opsForHash<String, String>().entries(key).collectMap({ it.key }, { it.value }).awaitSingle()

    suspend fun group(g: TestGroup): Map<String, String> = hash(keys.group(g.gid, date))

    suspend fun roster(g: TestGroup): Map<String, String> = hash(keys.members(g.gid, date))

    suspend fun isDayMember(g: TestGroup): Boolean =
        redis.opsForSet().isMember(keys.dayGroups(keys.bucket(g.gid), date), g.gid.value.toString()).awaitSingle()

    /** 이 Redis 를 통째로 비운다 — 게임 중 Redis 를 잃은 상황. */
    suspend fun flushAll() {
        redis.connectionFactory.reactiveConnection.use { it.serverCommands().flushAll().awaitSingle() }
    }

    /** 그룹 [g] 의 Stream 메시지(오래된 것부터) — 버킷 Stream 에서 gid 로 거른다. */
    suspend fun events(g: TestGroup): List<Map<String, String>> {
        val stream = keys.events(g.gid)
        if (!redis.hasKey(stream).awaitSingle()) return emptyList()
        return redis.opsForStream<String, String>().range(stream, Range.unbounded()).collectList().awaitSingle()
            .map { r -> r.value.entries.associate { it.key.toString() to it.value.toString() } }
            .filter { it["gid"] == g.gid.value.toString() }
    }

    /** 버킷 Stream 을 persister 가 끝까지 처리했다 — 마지막 메시지까지 배달 · ACK(pending 0). Stream 이 없으면 참. */
    suspend fun drained(g: TestGroup): Boolean {
        val stream = keys.events(g.gid)
        if (!redis.hasKey(stream).awaitSingle()) return true
        val last = redis.opsForStream<String, String>().reverseRange(stream, Range.unbounded()).take(1).collectList().awaitSingle()
            .firstOrNull()?.id?.value ?: return true
        val info = redis.opsForStream<String, String>().groups(stream).collectList().awaitSingle()
            .firstOrNull { it.groupName() == "persister" } ?: return false
        return info.lastDeliveredId() == last && info.pendingCount() == 0L
    }

    /**
     * 재적재한 그룹 Hash [after] 가 FLUSH 전 [before] 와 같다(ORACLE O22) — `version` 은 [fromMs] ~ [toMs](재적재 호출 전후 게임 시각)
     * 사이이고 옛 값보다 크다. `ranking` 은 JSON 으로 견준다(cjson 키 순서는 정해지지 않는다).
     */
    fun assertRestored(before: Map<String, String>, after: Map<String, String>, fromMs: Long, toMs: Long) {
        val skip = setOf("version", "ranking")
        assertThat(after.filterKeys { it !in skip }).isEqualTo(before.filterKeys { it !in skip })
        assertThat(after.containsKey("ranking")).isEqualTo(before.containsKey("ranking"))
        before["ranking"]?.let { assertThat(json.readTree(after.getValue("ranking"))).isEqualTo(json.readTree(it)) }
        assertThat(after).doesNotContainKey("pending")
        val v = after.getValue("version").toLong()
        assertThat(v).isBetween(fromMs, toMs).isGreaterThan(before.getValue("version").toLong())
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** 게임 시각을 [day] 의 [time] 으로 옮기는 `game.clock-offset` — 컨텍스트를 만들 때 한 번 계산한다. */
        fun offsetTo(day: LocalDate, time: LocalTime): String =
            Duration.between(Instant.now(), day.atTime(time).atZone(ZONE).toInstant()).toString()
    }
}
