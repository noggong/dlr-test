package com.delightroom.mission.app.api.platform

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 스키마 · 문제 시드 — ORACLE platform O13 · O14. 정본은 LLD platform §2.1 · §2.2.
 * `api` 기동(spring.sql.init)이 적용한 결과를 PostgreSQL 카탈로그로 본다. 컨텍스트는 auth 테스트와 같은 것을 나눠 쓴다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
class SchemaSeedTest {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var connectionFactory: ConnectionFactory

    // ---- O13 스키마 ----

    @Test
    fun `O13 테이블 8개가 있고 하루 단위 4개는 game_date 범위 파티션 부모다`() {
        val kinds = rows(
            "SELECT c.relname, c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'public' AND c.relname::text = ANY(:names)",
            "names" to TABLES.toTypedArray(),
        ) { it.get(0, String::class.java)!! to it.get(1, String::class.java)!! }.toMap()

        assertThat(kinds.keys).containsExactlyInAnyOrderElementsOf(TABLES)
        assertThat(kinds.filterValues { it == "p" }.keys).containsExactlyInAnyOrderElementsOf(DAILY_TABLES)
        assertThat(kinds.filterValues { it == "r" }.keys).containsExactlyInAnyOrderElementsOf(TABLES - DAILY_TABLES.toSet())

        val partitionKeys = strings(
            "SELECT c.relname || ':' || pg_get_partkeydef(c.oid) FROM pg_class c WHERE c.relname::text = ANY(:names)",
            "names" to DAILY_TABLES.toTypedArray(),
        )
        assertThat(partitionKeys).containsExactlyInAnyOrderElementsOf(DAILY_TABLES.map { "$it:RANGE (game_date)" })
    }

    @Test
    fun `O13 이름 붙은 인덱스 5개가 LLD 정의대로 있다`() {
        val defs = rows(
            "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname::text = ANY(:names)",
            "names" to INDEXES.keys.toTypedArray(),
        ) { it.get(0, String::class.java)!! to it.get(1, String::class.java)!! }.toMap()

        assertThat(defs.keys).containsExactlyInAnyOrderElementsOf(INDEXES.keys)
        INDEXES.forEach { (name, columns) -> assertThat(defs[name]).describedAs(name).endsWith(columns) }
    }

    @Test
    fun `O13 KST 오늘 파티션 4개가 있다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val names = DAILY_TABLES.map { "${it}_${today.format(DateTimeFormatter.BASIC_ISO_DATE)}" }
        val bounds = strings(
            "SELECT c.relname || ':' || pg_get_expr(c.relpartbound, c.oid) FROM pg_class c " +
                "WHERE c.relispartition AND c.relname::text = ANY(:names)",
            "names" to names.toTypedArray(),
        )
        assertThat(bounds).containsExactlyInAnyOrderElementsOf(
            names.map { "$it:FOR VALUES FROM ('$today') TO ('${today.plusDays(1)}')" },
        )
    }

    @Test
    fun `O13 모든 칸에 COMMENT 가 있고 CHECK 제약은 없다`() {
        val uncommented = strings(
            "SELECT a.attrelid::regclass::text || '.' || a.attname FROM pg_attribute a " +
                "WHERE a.attrelid = ANY(CAST(:tables AS regclass[])) AND a.attnum > 0 AND NOT a.attisdropped " +
                "AND col_description(a.attrelid, a.attnum) IS NULL",
            "tables" to TABLES.toTypedArray(),
        )
        assertThat(uncommented).isEmpty()
        assertThat(
            strings("SELECT obj_description(c.oid, 'pg_class') FROM pg_class c WHERE c.relname::text = ANY(:names)", "names" to TABLES.toTypedArray()),
        ).hasSize(TABLES.size).doesNotContain(NULL)
        assertThat(
            single(
                "SELECT col_description(a.attrelid, a.attnum) FROM pg_attribute a " +
                    "WHERE a.attrelid = 'daily_progress'::regclass AND a.attname = 'status'",
            ),
        )
            .contains("MemberStatus").doesNotContain("MissionStatus")

        val checks = strings(
            "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
        )
        assertThat(checks).isEmpty()
    }

    @Test
    fun `O13 schema sql · data sql 을 다시 적용해도 오류 없이 같다`() {
        val before = snapshot()
        runBlocking {
            ResourceDatabasePopulator(ClassPathResource("schema.sql"), ClassPathResource("data.sql"))
                .populate(connectionFactory).awaitSingleOrNull()
        }
        assertThat(snapshot()).isEqualTo(before)
    }

    // ---- O14 문제 시드 ----

    @Test
    fun `O14 난이도마다 120개이고 분야 4종이 다 있다`() {
        val perDifficulty = rows("SELECT difficulty, count(*) FROM questions GROUP BY difficulty") {
            (it.get(0) as Number).toInt() to (it.get(1) as Number).toLong()
        }.toMap()
        assertThat(perDifficulty).isEqualTo(mapOf(1 to SEED_PER_DIFFICULTY, 2 to SEED_PER_DIFFICULTY, 3 to SEED_PER_DIFFICULTY))

        val categories = rows("SELECT DISTINCT difficulty, category FROM questions") {
            (it.get(0) as Number).toInt() to it.get(1, String::class.java)!!
        }
        assertThat(categories.map { it.second }.toSet()).isEqualTo(CATEGORIES)
        (1..3).forEach { d -> assertThat(categories.filter { it.first == d }.map { it.second }.toSet()).isEqualTo(CATEGORIES) }
    }

    @Test
    fun `O14 pk_hash 는 겹치지 않고 answer_hash 는 prd D7 식과 같다`() {
        val questions = rows("SELECT pk_hash, answer, answer_hash FROM questions") {
            Triple(it.get(0, String::class.java)!!, it.get(1, String::class.java)!!, it.get(2, String::class.java)!!)
        }
        assertThat(questions).hasSize((SEED_PER_DIFFICULTY * 3).toInt())
        assertThat(questions.map { it.first }).doesNotHaveDuplicates().allMatch { HEX64.matches(it) }
        questions.forEach { (pk, answer, hash) ->
            assertThat(hash).describedAs(pk).matches(HEX64.pattern).isEqualTo(sha256Hex("$pk:${normalize(answer)}"))
        }
        // 정규화가 실제로 일을 한다(공백 · 대문자가 있는 답) — 원문 그대로 해시하면 달라야 한다
        assertThat(questions.first().let { (pk, answer, hash) -> sha256Hex("$pk:$answer") != hash }).isTrue()
    }

    // ---- 도우미 ----

    /** prd R-A2 — 띄어쓰기 전부 제거 → 소문자 → 앞뒤 공백 제거. */
    private fun normalize(answer: String): String = answer.filterNot { it.isWhitespace() }.lowercase().trim()

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun snapshot(): Map<String, Long> = mapOf(
        "tables" to count("SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r', 'p')"),
        "indexes" to count("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"),
        "partitions" to count("SELECT count(*) FROM pg_inherits"),
        "questions" to count("SELECT count(*) FROM questions"),
    )

    private fun count(sql: String): Long = rows(sql) { (it.get(0) as Number).toLong() }.single()

    private fun single(sql: String): String = strings(sql).single()

    /** 첫 칸을 문자열로 — SQL NULL 은 [NULL] 로 바꾼다(R2DBC 는 null 행을 못 낸다). */
    private fun strings(sql: String, vararg binds: Pair<String, Any>): List<String> =
        rows(sql, *binds) { it.get(0, String::class.java) ?: NULL }

    private fun <T : Any> rows(sql: String, vararg binds: Pair<String, Any>, map: (Readable) -> T): List<T> =
        runBlocking {
            binds.fold(db.sql(sql)) { spec, (k, v) -> spec.bind(k, v) }
                .map { row -> map(row) }
                .all().collectList().awaitSingle()
        }

    private companion object {
        val DAILY_TABLES = listOf("daily_progress", "solve_signals", "group_results", "group_result_members")
        val TABLES = listOf("groups", "users", "questions", "served_questions") + DAILY_TABLES
        val INDEXES = mapOf(
            "users_group_joined_idx" to "(group_id, joined_at)",
            "questions_difficulty_idx" to "(difficulty)",
            "daily_progress_group_idx" to "(game_date, group_id)",
            "group_results_group_idx" to "(group_id, game_date DESC)",
            "group_result_members_group_idx" to "(game_date, group_id)",
        )
        val CATEGORIES = setOf("POLITICS", "CURRENT", "SOCIETY", "GENERAL")
        const val SEED_PER_DIFFICULTY = 120L
        const val NULL = "<null>"
        val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
