package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.flow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 부하 · 메모리 테스트(ORACLE game-session O32 ~ O35)의 바탕 — 대량 고정 데이터와 `pg_stat_statements` 호출 수.
 *
 * 그룹 · 유저는 `generate_series` 한 문장으로 넣는다(행마다 왕복하면 10만 명에 몇 분). 그룹 이름은 [prefix] 로 시작해
 * 이 실행이 넣은 행만 다시 고른다 — 테스트끼리 DB 를 비우지 않는다.
 */
internal class LoadFixture(
    private val db: DatabaseClient,
    private val connectionFactory: ConnectionFactory,
) {
    /** api 와 같은 schema.sql · data.sql(문제 120개 × 3) + `pg_stat_statements` 확장(컨테이너가 미리 올려 둔다). */
    suspend fun schema() {
        ResourceDatabasePopulator(ClassPathResource("schema.sql"), ClassPathResource("data.sql"))
            .populate(connectionFactory).awaitSingleOrNull()
        db.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").then().awaitSingleOrNull()
    }

    /** [date] · 다음 날의 `daily_progress` 파티션 — 명단을 만들 때 Q10(-1 행)이 쓴다. */
    suspend fun partitions(date: LocalDate) {
        for (d in listOf(date, date.plusDays(1))) {
            db.sql(
                "CREATE TABLE IF NOT EXISTS daily_progress_${d.toString().replace("-", "")} PARTITION OF daily_progress " +
                    "FOR VALUES FROM ('$d') TO ('${d.plusDays(1)}')",
            ).then().awaitSingleOrNull()
        }
    }

    /** 그룹 [groups] 개 × 그룹원 [size] 명을 [joinedAt] 가입으로 넣는다. 그룹마다 그룹원 PK 오름차순. */
    suspend fun groups(prefix: String, groups: Int, size: Int, joinedAt: OffsetDateTime): Map<GroupId, List<UserId>> {
        db.sql(
            "INSERT INTO groups (name, name_key, member_count) " +
                "SELECT :p || g, lower(:p || g), :s FROM generate_series(1, :n) g",
        ).bind("p", prefix).bind("s", size).bind("n", groups).fetch().rowsUpdated().awaitSingle()
        db.sql(
            "INSERT INTO users (login_id, password_hash, group_id, joined_at) " +
                "SELECT lower(:p) || g.id || 'u' || s, 'x', g.id, :j FROM groups g CROSS JOIN generate_series(1, :s) s " +
                "WHERE g.name LIKE :p || '%'",
        ).bind("p", prefix).bind("s", size).bind("j", joinedAt).fetch().rowsUpdated().awaitSingle()
        return db.sql("SELECT u.group_id, u.id FROM users u JOIN groups g ON g.id = u.group_id WHERE g.name LIKE :p || '%' ORDER BY u.id")
            .bind("p", prefix)
            .map { row -> GroupId(row.get("group_id", Number::class.java)!!.toLong()) to UserId(row.get("id", Number::class.java)!!.toLong()) }
            .flow().toList()
            .groupBy({ it.first }, { it.second })
    }

    /**
     * [prefix] 그룹의 유저마다 출제 이력 [perUser] 개. 문제 목록에서 유저마다 다른 자리부터 12칸 간격으로 골라
     * 세 난이도에 고루 퍼진다(유저 안에서 겹치지 않음 — `perUser × 12 ≤ 문제 수`).
     */
    suspend fun serve(prefix: String, perUser: Int) {
        db.sql(
            "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                "SELECT u.id, q.a[1 + (u.id * 7 + s * 12) % cardinality(q.a)], DATE '2026-01-01', 1, 'SHOWN', now() " +
                "FROM users u JOIN groups g ON g.id = u.group_id CROSS JOIN generate_series(0, :n - 1) s " +
                "CROSS JOIN (SELECT array_agg(id ORDER BY id) AS a FROM questions) q WHERE g.name LIKE :p || '%'",
        ).bind("p", prefix).bind("n", perUser).fetch().rowsUpdated().awaitSingle()
    }

    /** `pg_stat_statements` 를 비운다 — 이 뒤의 [calls] 가 증가분이다. */
    suspend fun resetStatements() {
        db.sql("SELECT pg_stat_statements_reset()").then().awaitSingleOrNull()
    }

    /** 이 DB 의 전체 `calls` 합 — 측정 쿼리(`pg_stat_statements` 를 읽는 문장) 자신은 뺀다. */
    suspend fun calls(): Long =
        db.sql(
            "SELECT coalesce(sum(calls), 0) AS c FROM pg_stat_statements " +
                "WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) " +
                "AND query NOT ILIKE '%pg_stat_statements%'",
        ).map { row -> row.get("c", Number::class.java)!!.toLong() }.awaitOne()

    /** 문장별 호출 수(많은 순) — 실패 메시지 · 보고용. */
    suspend fun statements(): List<String> =
        db.sql(
            "SELECT calls, query FROM pg_stat_statements " +
                "WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) " +
                "AND query NOT ILIKE '%pg_stat_statements%' ORDER BY calls DESC LIMIT 20",
        ).map { row -> "${row.get("calls", Number::class.java)} × ${row.get("query", String::class.java)}" }.flow().toList()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** 실행마다 다른 그룹 이름 앞부분 — 같은 컨테이너에서 여러 번 돌아도 `name_key` 가 겹치지 않는다. */
        fun prefix(tag: String): String = "$tag-${Math.floorMod(System.nanoTime(), 46_656L).toString(36)}-"
    }
}
