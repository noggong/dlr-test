package com.delightroom.mission.app.api.platform

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.core.domain.group.GroupName
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.OffsetDateTime

/**
 * 로컬 회원 · 그룹 시드 — ORACLE platform O49 · LLD platform §2.3. 테스트마다 빈 DB 를 따로 만들어
 * `api` 기동과 같은 차례(schema.sql → data.sql → local-seed.sql)로 적용한다. 공유 DB 는 건드리지 않는다.
 */
class LocalSeedTest {
    @Test
    fun `O49 회원 500명이 2~10명 그룹에 빠짐없이 들고 member_count 가 실제 인원과 같다`() = withFreshDb("local_seed_a") { cf, db ->
        apply(cf, "schema.sql", "data.sql", "local-seed.sql")

        val logins = db.strings("SELECT login_id FROM users ORDER BY login_id")
        assertThat(logins).containsExactlyElementsOf((1..500).map { "seed%04d".format(it) })

        val groups = db.sql(
            "SELECT g.name, g.name_key, g.member_count, count(u.id) FROM groups g LEFT JOIN users u ON u.group_id = g.id " +
                "GROUP BY g.id ORDER BY g.id",
        ).map { r ->
            SeedGroup(r.get(0, String::class.java)!!, r.get(1, String::class.java)!!, r.get(2, Number::class.java)!!.toInt(), r.get(3, Number::class.java)!!.toInt())
        }.all().collectList().awaitSingle()
        assertThat(groups).allSatisfy { g ->
            assertThat(g.actual).isBetween(2, 10)
            assertThat(g.memberCount).isEqualTo(g.actual)
            assertThat(GroupName.parse(g.name).value).isEqualTo(g.name)
            assertThat(g.nameKey).isEqualTo(GroupName(g.name).key)
        }
        assertThat(groups.sumOf { it.actual }).isEqualTo(500)
        assertThat(groups.map { it.nameKey }).doesNotHaveDuplicates()

        val joined = db.sql("SELECT DISTINCT joined_at FROM users").map { r -> r.get(0, OffsetDateTime::class.java)!! }
            .all().collectList().awaitSingle()
        assertThat(joined.map { it.toInstant() }).containsExactly(OffsetDateTime.parse("2025-01-01T00:00:00+09:00").toInstant())

        val hashes = db.strings("SELECT DISTINCT password_hash FROM users")
        assertThat(hashes).hasSize(1)
        assertThat(hashes.single()).startsWith("\$2a\$10\$")
        assertThat(BCryptPasswordEncoder().matches(PASSWORD, hashes.single())).isTrue()
    }

    @Test
    fun `O49 다시 적용해도 행 수 · 시퀀스가 그대로다`() = withFreshDb("local_seed_b") { cf, db ->
        apply(cf, "schema.sql", "data.sql", "local-seed.sql")
        val before = db.snapshot()
        apply(cf, "schema.sql", "data.sql", "local-seed.sql")
        assertThat(db.snapshot()).isEqualTo(before)
        assertThat(before).startsWith(500L)
    }

    @Test
    fun `O49 시드 아이디나 그룹명이 이미 있으면 아무것도 넣지 않고 실제 사용자를 바꾸지 않는다`() {
        for ((round, existing) in listOf(
            "users" to "INSERT INTO users (login_id, password_hash, group_id) SELECT 'seed0003', 'x', id FROM groups",
            "groups" to "UPDATE groups SET name = '시드 그룹 007', name_key = '시드 그룹 007'",
        ).withIndex()) {
            withFreshDb("local_seed_c$round") { cf, db ->
                apply(cf, "schema.sql", "data.sql")
                db.sql("INSERT INTO groups (name, name_key, member_count) VALUES ('실제 그룹', '실제 그룹', 1)").then().awaitSingleOrNull()
                db.sql("INSERT INTO users (login_id, password_hash, group_id) SELECT 'real_user', 'x', id FROM groups").then().awaitSingleOrNull()
                db.sql(existing.second).then().awaitSingleOrNull()
                val before = db.strings("SELECT g.name || ':' || g.member_count || ':' || u.login_id FROM users u JOIN groups g ON g.id = u.group_id ORDER BY u.id")

                apply(cf, "local-seed.sql")

                assertThat(db.strings("SELECT g.name || ':' || g.member_count || ':' || u.login_id FROM users u JOIN groups g ON g.id = u.group_id ORDER BY u.id"))
                    .`as`(existing.first).isEqualTo(before)
                assertThat(db.strings("SELECT count(*)::text FROM groups")).containsExactly("1")
            }
        }
    }

    @Test
    fun `O49 두 api 가 빈 DB 에서 함께 시드를 적용해도 한 벌만 들어간다`() = withFreshDb("local_seed_e") { cf, db ->
        apply(cf, "schema.sql", "data.sql")
        val results = (0 until 2).map {
            async(Dispatchers.IO) { runCatching { apply(cf, "local-seed.sql") } }
        }.awaitAll()
        assertThat(results.mapNotNull { it.exceptionOrNull()?.message }).isEmpty()
        assertThat(db.snapshot()).startsWith(500L)
        assertThat(db.strings("SELECT count(*)::text FROM groups WHERE name LIKE '시드 그룹 %'")).containsExactly(SEED_GROUPS.toString())
    }

    @Test
    fun `O49 기본 설정은 시드를 넣지 않고 local-seed 프로필만 넣는다`() {
        val loader = YamlPropertySourceLoader()
        val base = loader.load("base", ClassPathResource("application.yml")).single()
        val seed = loader.load("seed", ClassPathResource("application-local-seed.yml")).single()
        assertThat(base.getProperty("spring.sql.init.data-locations")).isNull()
        assertThat(seed.getProperty("spring.sql.init.data-locations").toString())
            .isEqualTo("classpath:data.sql,classpath:local-seed.sql")
    }

    private data class SeedGroup(val name: String, val nameKey: String, val memberCount: Int, val actual: Int)

    private fun withFreshDb(name: String, block: suspend kotlinx.coroutines.CoroutineScope.(ConnectionFactory, DatabaseClient) -> Unit) =
        runBlocking {
            val admin = DatabaseClient.create(PostgresTestContainer.connectionFactory())
            admin.sql("DROP DATABASE IF EXISTS $name WITH (FORCE)").then().awaitSingleOrNull()
            admin.sql("CREATE DATABASE $name").then().awaitSingleOrNull()
            try {
                val cf = PostgresTestContainer.connectionFactory(name)
                block(cf, DatabaseClient.create(cf))
            } finally {
                admin.sql("DROP DATABASE IF EXISTS $name WITH (FORCE)").then().awaitSingleOrNull()
            }
        }

    private suspend fun apply(cf: ConnectionFactory, vararg scripts: String) {
        for (script in scripts) ResourceDatabasePopulator(ClassPathResource(script)).populate(cf).awaitSingleOrNull()
    }

    private suspend fun DatabaseClient.strings(sql: String): List<String> =
        sql(sql).map { r -> r.get(0, String::class.java)!! }.all().collectList().awaitSingle()

    /** 회원 수 · 그룹 수 · 두 시퀀스의 지금 값 */
    private suspend fun DatabaseClient.snapshot(): List<Long> =
        sql(
            "SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM groups), " +
                "(SELECT last_value FROM users_id_seq), (SELECT last_value FROM groups_id_seq)",
        ).map { r -> (0..3).map { r.get(it, Number::class.java)!!.toLong() } }.one().awaitSingle()

    private companion object {
        const val PASSWORD = "seed-pass-1"
        const val SEED_GROUPS = 87
    }
}
