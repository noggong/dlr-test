package com.delightroom.mission.app.worker.gamesession

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * game-session persister 테스트 도우미(ORACLE game-session O25 · O26 · I7) — 그룹 · 유저 · 문제 고르기, Lua `start` · `shown` 모양의
 * 이벤트 칸, `daily_progress` · `served_questions` 행 읽기, 그룹 버킷 Stream 읽기. 스키마 · 문제 시드는 `spring.sql.init` 이 적용한다.
 */
internal class GameSessionEventFixture(
    private val db: DatabaseClient,
    private val redis: ReactiveStringRedisTemplate,
    private val bucketCount: Int,
    private val date: LocalDate,
) {
    data class Q(val id: Long, val pk: String)

    data class TestGroup(val gid: Long, val uids: List<Long>, val logins: Map<Long, String>)

    /** 그룹원 [size] 명 그룹 — 가입 시각은 지금(실제 시각)이라 미래의 [date] 07:00 전 가입자다. */
    suspend fun group(size: Int): TestGroup {
        val tag = UUID.randomUUID().toString().take(8)
        val gid = db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, :n, :c) RETURNING id")
            .bind("n", "gs7-$tag").bind("c", size)
            .map { row -> row.get("id", Number::class.java)!!.toLong() }.one().awaitSingle()
        val logins = (1..size).associate { i ->
            val login = "gs7$tag$i"
            val uid = db.sql("INSERT INTO users (login_id, password_hash, group_id) VALUES (:l, 'x', :g) RETURNING id")
                .bind("l", login).bind("g", gid)
                .map { row -> row.get("id", Number::class.java)!!.toLong() }.one().awaitSingle()
            uid to login
        }
        return TestGroup(gid, logins.keys.toList(), logins)
    }

    /** 난이도 [difficulty] 의 문제 중 id 순 [offset] 번째. */
    suspend fun question(difficulty: Int, offset: Int = 0): Q =
        db.sql("SELECT id, pk_hash FROM questions WHERE difficulty = :d ORDER BY id LIMIT 1 OFFSET :o")
            .bind("d", difficulty).bind("o", offset)
            .map { row -> Q(row.get("id", Number::class.java)!!.toLong(), row.get("pk_hash", String::class.java)!!) }
            .one().awaitSingle()

    /** Lua `start` 7 이 내는 `mission.started` 칸. */
    fun started(g: TestGroup, uid: Long, startedUs: Long, late: Int): Map<String, String> =
        linkedMapOf(
            "type" to "mission.started", "date" to "$date", "gid" to "${g.gid}", "uid" to "$uid", "lid" to g.logins.getValue(uid),
            "version" to "1", "at" to "$startedUs", "startedAt" to "$startedUs", "late" to "$late",
        )

    /** Lua `shown` 6 이 내는 `question.shown` 칸. */
    fun shown(g: TestGroup, uid: Long, no: Int, q: Q, shownUs: Long): Map<String, String> =
        linkedMapOf(
            "type" to "question.shown", "date" to "$date", "gid" to "${g.gid}", "uid" to "$uid", "lid" to g.logins.getValue(uid),
            "version" to "1", "at" to "$shownUs", "questionNo" to "$no", "questionId" to q.pk, "qid" to "${q.id}",
            "shownAt" to "$shownUs",
        )

    suspend fun progressRows(uids: List<Long>): List<Map<String, Any?>> =
        db.sql("SELECT * FROM daily_progress WHERE game_date = :d AND user_id IN (${uids.joinToString()}) ORDER BY user_id")
            .bind("d", date).fetch().all().collectList().awaitSingle()

    suspend fun servedRows(uids: List<Long>): List<Map<String, Any?>> =
        db.sql("SELECT * FROM served_questions WHERE user_id IN (${uids.joinToString()}) ORDER BY user_id, question_id")
            .fetch().all().collectList().awaitSingle()

    fun stream(g: TestGroup): String = "ev:{${Math.floorMod(g.gid, bucketCount)}}"

    /** 그룹 버킷 Stream 에 남은 이 그룹의 메시지(id 순) — persister 가 ACK 하면 트림되므로 루프를 끈 테스트에서만 전부다. */
    suspend fun events(g: TestGroup): List<MapRecord<String, String, String>> =
        redis.opsForStream<String, String>().range(stream(g), Range.unbounded<String>()).collectList().awaitSingle()
            .filter { it.value["gid"] == "${g.gid}" }

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** [date] 07:00 + [seconds] 초 + [micros] µs 의 epoch µs. */
        fun startUs(date: LocalDate, seconds: Long, micros: Long): Long {
            val at = date.atTime(LocalTime.of(7, 0)).atZone(KST).toInstant().plusSeconds(seconds)
            return at.epochSecond * 1_000_000 + micros
        }

        fun usToInstant(us: Long): Instant = Instant.ofEpochSecond(us / 1_000_000, (us % 1_000_000) * 1_000)

        /** `game.clock-offset` — 지금이 [date] 07:05 KST 가 되게(Lua `start` · `shown` 의 창 안). */
        fun offsetTo0705(date: LocalDate): String =
            Duration.between(Instant.now(), date.atTime(LocalTime.of(7, 5)).atZone(KST).toInstant()).toString()
    }
}
