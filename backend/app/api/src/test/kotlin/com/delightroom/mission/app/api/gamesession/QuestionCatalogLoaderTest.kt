package com.delightroom.mission.app.api.gamesession

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionCatalog
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ContextConfiguration

/**
 * 문제 캐시 적재(LLD game-session §1 `QuestionCatalogLoader` · Q1) — `api` 기동이 문제 시드(T-PF-1 data.sql) 뒤에 `questions` 전체를 올린다.
 * 은행 응답의 `questionId` · `answerHash`(O13 · O14)가 이 캐시에서 나온다. 컨텍스트는 같은 설정(PostgreSQL 만)인 RouteSmokeTest · SchemaSeedTest 와 나눠 쓴다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
class QuestionCatalogLoaderTest {
    @Autowired
    private lateinit var catalog: QuestionCatalog

    @Autowired
    private lateinit var db: DatabaseClient

    private data class Row(val id: Long, val pk: String, val difficulty: Int, val category: String, val body: String, val answerHash: String)

    private fun rows(): List<Row> = runBlocking {
        db.sql("SELECT id, pk_hash, difficulty, category, body, answer_hash FROM questions ORDER BY id")
            .map { r ->
                Row(
                    r.get("id", Number::class.java)!!.toLong(),
                    r.get("pk_hash", String::class.java)!!,
                    r.get("difficulty", Number::class.java)!!.toInt(),
                    r.get("category", String::class.java)!!,
                    r.get("body", String::class.java)!!,
                    r.get("answer_hash", String::class.java)!!,
                )
            }
            .flow()
            .toList()
    }

    @Test
    fun `O13 (바탕) 기동 때 questions 전체가 문제 캐시에 오른다 — 시드 난이도마다 120개 이상`() {
        val rows = rows()

        assertThat(catalog.size).isEqualTo(rows.size)
        for (d in Difficulty.entries) {
            assertThat(catalog.idsByDifficulty.getValue(d))
                .hasSizeGreaterThanOrEqualTo(SEED_PER_DIFFICULTY)
                .containsExactly(*rows.filter { it.difficulty == d.level }.map { it.id }.toLongArray())
        }
    }

    @Test
    fun `O13 (바탕) 캐시의 PK · 난이도 · 분야 · 본문 · answerHash 가 questions 행과 같다`() {
        for (row in rows()) {
            val q = catalog.byId.getValue(row.id)
            assertThat(q.id.value).isEqualTo(row.pk).hasSize(64)
            assertThat(catalog.byPk.getValue(q.id)).isSameAs(q)
            assertThat(q.difficulty.level).isEqualTo(row.difficulty)
            assertThat(q.category.name).isEqualTo(row.category)
            assertThat(q.body).isEqualTo(row.body)
            assertThat(q.answerHash).isEqualTo(row.answerHash).hasSize(64)
        }
    }

    private companion object {
        const val SEED_PER_DIFFICULTY = 120
    }
}
