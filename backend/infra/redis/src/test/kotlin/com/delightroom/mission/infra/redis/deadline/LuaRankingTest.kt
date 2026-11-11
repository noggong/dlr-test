package com.delightroom.mission.infra.redis.deadline

import com.delightroom.mission.core.domain.ranking.Ranking
import com.delightroom.mission.core.domain.ranking.RankingKey
import com.delightroom.mission.core.testfixture.RankingCases
import com.delightroom.mission.core.testfixture.RankingRandomGroups
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * ORACLE deadline-result O2 · O3 · O4(Lua) · I6 · I7 — `lib/ranking.lua` 를 테스트 래퍼 `rank_only.lua` 로 `EVAL` 하고
 * Kotlin `Ranking` 과 같은 입력(`ranking-cases.json` · 시드 `20261111` 무작위 그룹)을 넣어 비교한다.
 */
class LuaRankingTest {
    private val redis = RedisTestContainer.template
    private val scripts = RedisScripts(redis)

    /** 표시 순서대로 (로그인 아이디, 순위). */
    private data class Row(val login: String, val rank: Int)

    /** 그룹 여럿을 한 번에 `rank_only` 로 — 그룹마다 표시 순서의 (아이디, 순위). */
    private fun luaRank(groups: List<List<RankingKey>>): List<List<Row>> = runBlocking {
        val args = groups.flatMap { keys -> listOf(keys.size.toString()) + keys.flatMap(::argv) }
        scripts.eval("rank_only", emptyList(), args).map { group ->
            (group as List<*>).chunked(2) { (login, rank) -> Row(login as String, (rank as Long).toInt()) }
        }
    }

    private fun argv(key: RankingKey): List<String> = listOf(
        key.loginId.value,
        if (key.cheatFailed) "1" else "0",
        key.progress.toString(),
        key.lastSolvedAtUs?.toString() ?: "",
        key.passNo?.toString() ?: "",
        if (key.late) "1" else "0",
    )

    private fun kotlinRank(keys: List<RankingKey>): List<Row> =
        Ranking.rank(keys).map { Row(it.key.loginId.value, it.rank) }

    @TestFactory
    fun `O2 Lua 랭킹이 경계 표대로`(): List<DynamicTest> =
        RankingCases.groups.map { group ->
            DynamicTest.dynamicTest("O2 ${group.id}") {
                val keys = group.members.map(RankingCases::key)
                val expected = group.members.sortedBy { it.expectedOrder }.map { Row(it.loginId, it.expectedRank) }
                // 입력 순서와 무관해야 한다: 표 순서 · 역순 · 시드 고정 섞기
                val inputs = listOf(keys, keys.reversed()) + (1..20).map { keys.shuffled(kotlin.random.Random(20261111 + it)) }
                luaRank(inputs).forEach { assertThat(it).containsExactlyElementsOf(expected) }
            }
        }

    @Test
    fun `O2 경계 표 23행 모두 Lua 와 표가 같다`() {
        val expected = RankingCases.groups.map { g -> g.members.sortedBy { it.expectedOrder }.map { Row(it.loginId, it.expectedRank) } }
        val actual = luaRank(RankingCases.groups.map { g -> g.members.map(RankingCases::key) })
        assertThat(actual.sumOf { it.size }).isEqualTo(23)
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `O4 Lua 도 같은 순위 안은 바이트 순이고 다음 순위는 동률 수만큼 건너뛴다`() {
        val g2 = RankingCases.groups.single { it.id == "G2" }
        val rows = luaRank(listOf(g2.members.map(RankingCases::key).reversed())).single()
        val logins = rows.map { it.login }
        assertThat(logins.indexOf("bo_1")).isEqualTo(logins.indexOf("bo11") + 1)
        assertThat(listOf("bo11", "bo_1", "cy_1").map { l -> rows.single { it.login == l }.rank }).containsExactly(2, 2, 4)
    }

    @Test
    fun `O3 I6 시드 20261111 무작위 10,000 그룹에서 Lua 와 Kotlin 이 같다`() {
        val groups = RankingRandomGroups.generate()
        assertThat(groups).hasSize(10_000)
        val lua = groups.chunked(500).flatMap { chunk -> luaRank(chunk.map { it.keys }) }

        val mismatches = groups.zip(lua).filter { (group, rows) -> kotlinRank(group.keys) != rows }
        assertThat(mismatches.map { it.first.index }).`as`("불일치 그룹").isEmpty()
    }

    @Test
    fun `I7 무작위 그룹의 Lua 순위는 경쟁 순위 성질을 지킨다`() {
        val groups = RankingRandomGroups.generate()
        val lua = groups.chunked(500).flatMap { chunk -> luaRank(chunk.map { it.keys }) }
        groups.zip(lua).forEach { (group, rows) ->
            val keyOf = group.keys.associateBy { it.loginId.value }
            rows.forEach { row ->
                val above = rows.count { Ranking.compare(keyOf.getValue(it.login), keyOf.getValue(row.login)) < 0 }
                assertThat(row.rank).`as`("group ${group.index} ${row.login}").isEqualTo(1 + above)
            }
            rows.zipWithNext { a, b ->
                if (Ranking.compare(keyOf.getValue(a.login), keyOf.getValue(b.login)) == 0) assertThat(b.rank).isEqualTo(a.rank)
            }
        }
    }

    @Test
    fun `fromHash · encode 는 그룹 Hash 에서 읽어 LLD 2·2 랭킹 JSON 을 낸다`() = runBlocking {
        val key = "g:{1}:t-dr-2:2026-11-11"
        val g1 = RankingCases.groups.single { it.id == "G1" }
        val uids = g1.members.indices.map { "u$it" }
        val fields = HashMap<String, String>()
        g1.members.forEachIndexed { i, m ->
            val k = RankingCases.key(m)
            fields["u$i:lid"] = m.loginId
            fields["u$i:st"] = m.status
            fields["u$i:p"] = m.progress.toString()
            if (m.startedAt != null) fields["u$i:late"] = if (k.late) "1" else "0"
            k.lastSolvedAtUs?.let { fields["u$i:ls"] = it.toString() }
            k.passNo?.let { fields["u$i:pn"] = it.toString() }
        }
        redis.opsForHash<String, String>().putAll(key, fields).awaitSingle()
        try {
            val json = scripts.eval("rank_hash", listOf(key), uids.reversed()).single() as String
            val rows: List<Map<String, Any?>> = jacksonObjectMapper().readValue(json)

            val expected = g1.members.sortedBy { it.expectedOrder }
            assertThat(rows.map { it["u"] }).containsExactlyElementsOf(expected.map { it.loginId })
            rows.zip(expected).forEach { (row, m) ->
                val k = RankingCases.key(m)
                assertThat(row.keys).containsExactlyInAnyOrder("id", "u", "r", "p", "s", "l", "n")
                assertThat(row["id"]).isEqualTo("u${g1.members.indexOf(m)}")
                assertThat(row["r"]).isEqualTo(m.expectedRank)
                assertThat(row["p"]).isEqualTo(m.progress)
                assertThat(row["s"]).isEqualTo(m.status)
                // 시각은 µs 16자리 문자열 그대로(cjson 숫자는 14자리로 줄어든다)
                assertThat(row["l"]).isEqualTo(k.lastSolvedAtUs?.toString())
                assertThat(row["n"]).isEqualTo(m.passNo)
            }
        } finally {
            redis.delete(key).awaitSingle()
        }
        Unit
    }

    @Test
    fun `fromHash 는 진행도 1 이상인데 마지막 풀이 시각이 없으면 오류로 끝낸다`() = runBlocking {
        val key = "g:{1}:t-dr-2-bad:2026-11-11"
        redis.opsForHash<String, String>().putAll(key, mapOf("u0:lid" to "amy0", "u0:st" to "FAILED", "u0:p" to "2")).awaitSingle()
        try {
            assertThatThrownBy { runBlocking { scripts.eval("rank_hash", listOf(key), listOf("u0")) } }
                .hasStackTraceContaining("progress >= 1 needs ls")
        } finally {
            redis.delete(key).awaitSingle()
        }
        Unit
    }

    @Test
    fun `encode 는 빈 그룹을 빈 배열로 낸다`() = runBlocking {
        assertThat(scripts.eval("rank_hash", listOf("g:{1}:t-dr-2-none:2026-11-11"), emptyList()).single()).isEqualTo("[]")
        Unit
    }
}
