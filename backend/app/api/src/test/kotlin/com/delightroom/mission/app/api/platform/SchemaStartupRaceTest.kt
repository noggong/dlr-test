package com.delightroom.mission.app.api.platform

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.infra.rdb.RdbInfraAutoConfiguration
import com.delightroom.mission.infra.rdb.SchemaLock
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 기동 경합 — ORACLE platform O36 · LLD platform §4.3(T-PF-12).
 * 빈 DB 에서 `api` 둘의 스키마 적용(spring.sql.init 과 같은 `ResourceDatabasePopulator` · schema.sql → data.sql)과
 * `worker` 둘의 파티션 생성(`DailyProgressStore.ensurePartitions` — Q9)을 동시에 돌린다. 라운드마다 새 DB 를 만들고 지운다.
 * 스키마가 아직 없어 나는 `42P01`(relation does not exist)만 허용한다 — deadlock(`40P01`) · 카탈로그 중복(`23505`) 등은 실패다.
 */
class SchemaStartupRaceTest {
    @Test
    fun `O36 schema_sql 은 처음에 스키마 잠금을 잡고 끝에서 푼다`() {
        val statements = ClassPathResource("schema.sql").getContentAsString(Charsets.UTF_8)
            .lines().filterNot { it.isBlank() || it.trimStart().startsWith("--") }
        assertThat(statements.first()).isEqualTo("SELECT pg_advisory_lock(${SchemaLock.KEY});")
        assertThat(statements.last()).isEqualTo("SELECT pg_advisory_unlock(${SchemaLock.KEY});")
    }

    @Test
    fun `O36 빈 DB 에서 api 둘의 스키마 적용과 worker 둘의 파티션 생성이 겹쳐도 모두 성공한다`() = runBlocking {
        val admin = DatabaseClient.create(PostgresTestContainer.connectionFactory())
        repeat(ROUNDS) { round ->
            val name = "startup_race_$round"
            admin.sql("DROP DATABASE IF EXISTS $name WITH (FORCE)").then().awaitSingleOrNull()
            admin.sql("CREATE DATABASE $name").then().awaitSingleOrNull()
            try {
                race(PostgresTestContainer.connectionFactory(name), round)
            } finally {
                admin.sql("DROP DATABASE IF EXISTS $name WITH (FORCE)").then().awaitSingleOrNull()
            }
        }
    }

    private suspend fun race(cf: ConnectionFactory, round: Int) = coroutineScope {
        val db = DatabaseClient.create(cf)
        val store = RdbInfraAutoConfiguration().dailyProgressStore(db)
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val schemaDone = AtomicBoolean(false)
        val workerErrors = Collections.synchronizedList(mutableListOf<String>())
        // worker: 오늘 · 내일(기동 파티션) 다음에는 날마다 새 날짜 — 매번 실제로 파티션을 만들어 스키마 적용 내내 겹친다
        val workers = (0 until WORKERS).map { w ->
            launch(Dispatchers.IO) {
                var n = 0
                while (!schemaDone.get()) {
                    val date = if (n < 2) today.plusDays(n.toLong()) else FAR.plusDays((w * 10_000 + n).toLong())
                    try {
                        store.ensurePartitions(date)
                    } catch (e: Exception) {
                        if (sqlState(e) != "42P01") workerErrors += "round=$round worker=$w date=$date ${sqlState(e)} ${e.message}"
                    }
                    n++
                }
                // 스키마가 다 된 뒤에는 실패 없이 만든다
                store.ensurePartitions(FAR.minusDays(1L + w))
            }
        }
        val apis = (0 until APIS).map {
            async(Dispatchers.IO) {
                runCatching {
                    ResourceDatabasePopulator(ClassPathResource("schema.sql")).populate(cf).awaitSingleOrNull()
                    ResourceDatabasePopulator(ClassPathResource("data.sql")).populate(cf).awaitSingleOrNull()
                }
            }
        }
        val results = apis.awaitAll()
        schemaDone.set(true)
        workers.forEach { it.join() }

        assertThat(results.mapNotNull { r -> r.exceptionOrNull()?.let { "round=$round api ${sqlState(it)} ${it.message}" } }).isEmpty()
        assertThat(workerErrors).isEmpty()
        val seeded = db.sql("SELECT count(*) FROM questions").map { r -> r.get(0, Number::class.java)!!.toLong() }.one().awaitSingle()
        assertThat(seeded).isEqualTo(360L)
    }

    private fun sqlState(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<R2dbcException>().firstOrNull()?.sqlState

    private companion object {
        const val ROUNDS = 10
        const val APIS = 2
        const val WORKERS = 2
        val FAR: LocalDate = LocalDate.of(2040, 1, 1)
    }
}
