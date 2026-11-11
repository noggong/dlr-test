package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.domain.event.StreamEvent
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 테스트 그룹 하나 — 그룹원은 [uids] 순서, 문제 PK 는 번호(1~3)마다 [questions]. */
data class SignalGroup(
    val date: LocalDate,
    val gid: Long,
    val uids: List<Long>,
    val questions: Map<Int, Pair<Long, String>>,
)

/**
 * solve-signal persister 테스트 도우미 — 스키마는 `spring.sql.init`(schema.sql · data.sql 문제 시드)이 적용하고,
 * 이 도우미는 시험 날짜의 하루 파티션 · 그룹 · 유저 · `daily_progress` · `served_questions`(SHOWN) 를 만든다.
 * 이벤트 칸은 Lua `solve` · `pass` · `fail` 이 내는 모양(LLD solve-signal §2.2 — 시각은 µs 십진 문자열)이다.
 */
class SignalFixture(
    private val db: DatabaseClient,
    private val redis: ReactiveStringRedisTemplate,
    private val bucketCount: Int,
) {
    private val clock = AtomicLong(DAY_US)

    /** 그룹원 [size] 명 그룹 — 모두 `IN_PROGRESS` · 진행도 0, 1~3번 문제를 각자 SHOWN 으로 받아 둔 상태. */
    suspend fun group(size: Int, date: LocalDate = DATE): SignalGroup {
        partitions(date)
        val tag = UUID.randomUUID().toString().take(8)
        val gid = db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, :n, :c) RETURNING id")
            .bind("n", "ss4-$tag").bind("c", size)
            .map { row -> row.get("id", Number::class.java)!!.toLong() }.one().awaitSingle()
        val questions = (1..3).associateWith { no ->
            db.sql("SELECT id, pk_hash FROM questions WHERE difficulty = :d ORDER BY id LIMIT 1")
                .bind("d", no)
                .map { row -> row.get("id", Number::class.java)!!.toLong() to row.get("pk_hash", String::class.java)!! }
                .one().awaitSingle()
        }
        val uids = (1..size).map { i ->
            val uid = db.sql("INSERT INTO users (login_id, password_hash, group_id) VALUES (:l, 'x', :g) RETURNING id")
                .bind("l", "ss4$tag$i").bind("g", gid)
                .map { row -> row.get("id", Number::class.java)!!.toLong() }.one().awaitSingle()
            db.sql(
                "INSERT INTO daily_progress (game_date, user_id, group_id, progress, status, started_at, is_late) " +
                    "VALUES (:d, :u, :g, 0, 'IN_PROGRESS', now(), false)",
            ).bind("d", date).bind("u", uid).bind("g", gid).then().awaitFirstOrNull()
            questions.forEach { (no, q) ->
                db.sql(
                    "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                        "VALUES (:u, :q, :d, :no, 'SHOWN', now())",
                ).bind("u", uid).bind("q", q.first).bind("d", date).bind("no", no).then().awaitFirstOrNull()
            }
            uid
        }
        return SignalGroup(date, gid, uids, questions)
    }

    suspend fun setStatus(g: SignalGroup, uid: Long, status: String, progress: Int) {
        db.sql("UPDATE daily_progress SET status = :s, progress = :p WHERE game_date = :d AND user_id = :u")
            .bind("s", status).bind("p", progress).bind("d", g.date).bind("u", uid).then().awaitFirstOrNull()
    }

    /** 다음 µs 시각(늘 커진다). */
    fun nextUs(): Long = clock.addAndGet(1_000)

    fun solve(
        g: SignalGroup,
        uid: Long,
        no: Int,
        at: Long = nextUs(),
        successTime: String = "2030-01-15T07:03:10.412+09:00",
    ): Map<String, String> =
        common(g, "solve.applied", uid, at) + mapOf(
            "questionNo" to "$no",
            "questionId" to g.questions.getValue(no).second,
            "successTime" to successTime,
            "clickedAt" to "${at - 500_000}",
            "arrivedAt" to "$at",
            "progress" to "$no",
            "status" to if (no == 3) "SUCCEEDED" else "IN_PROGRESS",
            "grace" to "0",
        )

    fun pass(g: SignalGroup, uid: Long, no: Int, at: Long = nextUs()): Map<String, String> =
        common(g, "pass.used", uid, at) + mapOf("questionNo" to "$no", "questionId" to g.questions.getValue(no).second)

    fun cheatFail(g: SignalGroup, uid: Long, progress: Int, at: Long = nextUs()): Map<String, String> =
        common(g, "member.cheat_failed", uid, at) + mapOf("progress" to "$progress", "cheatFailedAt" to "$at")

    /** 그룹원마다 (상태, 진행도, 마지막 풀이 µs, 패스 번호) — 순위는 넘긴 순서. */
    fun decided(
        g: SignalGroup,
        result: String,
        members: List<Member>,
        at: Long = nextUs(),
        rankingFinal: Int = 1,
    ): Map<String, String> {
        val ranking = rankingJson(members)
        return common(g, "group.decided", members.last().uid, at) + mapOf(
            "result" to result,
            "decidedAt" to "$at",
            "memberCount" to "${members.size}",
            "rankingFinal" to "$rankingFinal",
            "ranking" to ranking,
        )
    }

    /** `group.ranking_final`(Lua `deadline` 6.2 모양) — `memberCount` 칸 없음 · `rankingFinal` 1. 순위는 넘긴 순서. */
    fun rankingFinal(g: SignalGroup, result: String, decidedAt: Long, members: List<Member>, at: Long = nextUs()): Map<String, String> =
        common(g, "group.ranking_final", members.last().uid, at) + mapOf(
            "uid" to "",
            "lid" to "",
            "result" to result,
            "decidedAt" to "$decidedAt",
            "rankingFinal" to "1",
            "ranking" to rankingJson(members),
        )

    private fun rankingJson(members: List<Member>): String =
        members.mapIndexed { i, m ->
            val l = m.lastSolvedUs?.let { "\"$it\"" } ?: "null"
            val n = m.passNo?.toString() ?: "null"
            """{"id":"${m.uid}","u":"ss4-${m.uid}","r":${m.rank ?: (i + 1)},"p":${m.progress},"s":"${m.status}","l":$l,"n":$n}"""
        }.joinToString(",", "[", "]")

    /** [rank] 를 비우면 넘긴 순서가 순위다. */
    data class Member(
        val uid: Long,
        val status: String,
        val progress: Int,
        val lastSolvedUs: Long? = null,
        val passNo: Int? = null,
        val rank: Int? = null,
    )

    private fun common(g: SignalGroup, type: String, uid: Long, at: Long): Map<String, String> =
        linkedMapOf(
            "type" to type, "date" to "${g.date}", "gid" to "${g.gid}", "uid" to "$uid",
            "lid" to "ss4-$uid", "version" to "1", "at" to "$at",
        )

    fun event(fields: Map<String, String>): StreamEvent = StreamEvent("0-1", fields)

    fun bucket(g: SignalGroup): Int = (g.gid % bucketCount).toInt()

    /** 그룹 버킷 Stream 에 넣는다 — 소비 루프가 처리한다. */
    suspend fun xadd(g: SignalGroup, fields: Map<String, String>): String =
        redis.opsForStream<String, String>().add(MapRecord.create("ev:{${bucket(g)}}", fields)).awaitSingle().value

    suspend fun pending(g: SignalGroup): Long =
        redis.opsForStream<String, String>().pending("ev:{${bucket(g)}}", "persister").awaitSingle().totalPendingMessages

    /** 표 [table] 에서 이 그룹(또는 그룹원)의 행 전부 — 비교용(정렬 고정). */
    suspend fun rows(table: String, g: SignalGroup): List<Map<String, Any?>> {
        val where = when (table) {
            "group_results", "group_result_members" -> "game_date = :d AND group_id = ${g.gid}"
            "served_questions" -> "user_id IN (${g.uids.joinToString()})"
            else -> "game_date = :d AND user_id IN (${g.uids.joinToString()})"
        }
        val order = if (table == "group_results") "group_id" else if (table == "served_questions") "user_id, question_id" else "user_id"
        var spec = db.sql("SELECT * FROM $table WHERE $where ORDER BY $order")
        if (":d" in where) spec = spec.bind("d", g.date)
        return spec.fetch().all().collectList().awaitSingle()
    }

    /** 그룹원 uid → `users.login_id`. */
    suspend fun loginIds(g: SignalGroup): Map<Long, String> =
        db.sql("SELECT id, login_id FROM users WHERE group_id = ${g.gid}")
            .map { row -> row.get("id", Number::class.java)!!.toLong() to row.get("login_id", String::class.java)!! }
            .all().collectList().awaitSingle().toMap()

    suspend fun progress(g: SignalGroup, uid: Long): Map<String, Any?> =
        rows("daily_progress", g).single { (it["user_id"] as Number).toLong() == uid }

    /** [date] 의 하루 파티션 4개(이미 있으면 그대로). */
    suspend fun partitions(date: LocalDate) {
        val suffix = date.toString().replace("-", "")
        for (t in listOf("daily_progress", "solve_signals", "group_results", "group_result_members")) {
            db.sql("CREATE TABLE IF NOT EXISTS ${t}_$suffix PARTITION OF $t FOR VALUES FROM ('$date') TO ('${date.plusDays(1)}')")
                .then().awaitFirstOrNull()
        }
    }

    companion object {
        /** 시험 날짜 — 기동 때 만들어지는 오늘 · 내일 파티션과 겹치지 않는다. */
        val DATE: LocalDate = LocalDate.of(2030, 1, 15)

        /** [DATE] 07:00 KST 근처의 µs. */
        val DAY_US: Long = Instant.parse("2030-01-14T22:00:00Z").toEpochMilli() * 1_000

        fun usToInstant(us: Long): Instant = Instant.ofEpochSecond(us / 1_000_000, (us % 1_000_000) * 1_000)
    }
}
