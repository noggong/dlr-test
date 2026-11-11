package com.delightroom.mission.core.domain.ranking

import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.testfixture.RankingCases
import com.delightroom.mission.core.testfixture.RankingRandomGroups
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random

/**
 * ORACLE deadline-result O1 · O4(Kotlin) — 경계 사례 표 `ranking-cases.json`.
 * O3 · I7 의 Kotlin 쪽 — 무작위 그룹 생성기 `RankingRandomGroups`(Lua 일치는 infra:redis `LuaRankingTest`).
 */
class RankingTest {
    @Test
    fun `O1 경계 표는 23행이고 지각 계산이 표와 같다`() {
        val members = RankingCases.groups.flatMap { it.members }
        assertThat(members).hasSize(23)
        assertThat(members.map { it.loginId }).doesNotHaveDuplicates()
        members.forEach { assertThat(RankingCases.late(it)).`as`(it.loginId).isEqualTo(it.expectedLate) }
    }

    @TestFactory
    fun `O1 Kotlin 랭킹이 경계 표대로`(): List<DynamicTest> =
        RankingCases.groups.map { group ->
            DynamicTest.dynamicTest("O1 ${group.id}") {
                val keys = group.members.map(RankingCases::key)
                val expectedRank = group.members.associate { it.loginId to it.expectedRank }
                val expectedOrder = group.members.sortedBy { it.expectedOrder }.map { it.loginId }

                // 입력 순서와 무관해야 한다: 표 순서 · 역순 · 시드 고정 섞기 20벌
                val inputs = listOf(keys, keys.reversed()) + (1..20).map { keys.shuffled(Random(20261111 + it)) }
                inputs.forEach { input ->
                    val ranked = Ranking.rank(input)
                    assertThat(ranked.associate { it.key.loginId.value to it.rank }).isEqualTo(expectedRank)
                    assertThat(ranked.map { it.key.loginId.value }).containsExactlyElementsOf(expectedOrder)
                }
            }
        }

    @Test
    fun `O4 같은 순위 안은 바이트 순이고 다음 순위는 동률 수만큼 건너뛴다`() {
        val g2 = RankingCases.groups.single { it.id == "G2" }
        val ranked = Ranking.rank(g2.members.map(RankingCases::key).reversed())
        val logins = ranked.map { it.key.loginId.value }
        val rankOf = ranked.associate { it.key.loginId.value to it.rank }
        assertThat(logins.indexOf("bo_1")).isEqualTo(logins.indexOf("bo11") + 1)
        assertThat(listOf("bo11", "bo_1", "cy_1").map(rankOf::getValue)).containsExactly(2, 2, 4)
    }

    @Test
    fun `O3 무작위 그룹은 시드가 같으면 같고 경계 키를 고루 담는다`() {
        val groups = RankingRandomGroups.generate()
        assertThat(groups).hasSize(10_000)
        assertThat(RankingRandomGroups.generate()).isEqualTo(groups)

        val members = groups.flatMap { it.members }
        assertThat(groups.map { it.members.size }.toSet()).isEqualTo((2..10).toSet())
        assertThat(members.map { it.status }.toSet())
            .containsExactlyInAnyOrder("NOT_STARTED", "IN_PROGRESS", "SUCCEEDED", "CHEAT_FAILED", "FAILED")
        assertThat(members.map { it.key.progress }.toSet()).isEqualTo((-1..3).toSet())
        assertThat(members.map { it.key.passNo }.toSet()).containsExactlyInAnyOrder(null, 1, 2, 3)
        assertThat(members.map { it.key.late }.toSet()).containsExactlyInAnyOrder(true, false)
        groups.forEach { g -> assertThat(g.members.map { it.key.loginId }).doesNotHaveDuplicates() }
        // 동시각 · 1µs 차이가 실제로 나온다(2순위 동률과 그 다음 키를 본다)
        val timed = groups.map { g -> g.keys.mapNotNull { it.lastSolvedAtUs } }
        assertThat(timed.count { it.size != it.toSet().size }).isGreaterThan(1_000)
        assertThat(timed.count { ts -> ts.any { t -> (t + 1) in ts } }).isGreaterThan(1_000)
    }

    @Test
    fun `I7 무작위 그룹의 Kotlin 순위는 경쟁 순위 성질을 지키고 입력 순서와 무관하다`() {
        RankingRandomGroups.generate().forEach { group ->
            val ranked = Ranking.rank(group.keys)
            assertCompetitionRanks(ranked)
            assertThat(Ranking.rank(group.keys.shuffled(Random(group.index)))).isEqualTo(ranked)
        }
    }

    @Test
    fun `compare 는 반대칭이고 추이적이다`() {
        val keys = RankingCases.groups.flatMap { it.members }.map(RankingCases::key)
        fun sign(a: RankingKey, b: RankingKey) = Integer.signum(Ranking.compare(a, b))
        for (a in keys) for (b in keys) {
            assertThat(sign(a, b)).isEqualTo(-sign(b, a))
            for (c in keys) {
                if (sign(a, b) <= 0 && sign(b, c) <= 0) assertThat(sign(a, c)).isLessThanOrEqualTo(0)
                if (sign(a, b) == 0 && sign(b, c) == 0) assertThat(sign(a, c)).isEqualTo(0)
            }
        }
    }

    @Test
    fun `진행도 1~3 은 지각을 보지 않는다`() {
        val onTime = key("on00", progress = 2, lastSolvedAtUs = 100, late = false)
        val late = key("la00", progress = 2, lastSolvedAtUs = 100, late = true)
        assertThat(Ranking.compare(onTime, late)).isZero()
        assertThat(Ranking.rank(listOf(onTime, late)).map { it.rank }).containsExactly(1, 1)
    }

    @Test
    fun `진행도 0 은 둘 다 패스면 번호와 무관하게 동률`() {
        val pass1 = key("p100", progress = 0, passNo = 1)
        val pass2 = key("p200", progress = 0, passNo = 2)
        assertThat(Ranking.compare(pass1, pass2)).isZero()
    }

    @Test
    fun `진행도 -1 은 지각 · 패스와 무관하게 동률`() {
        assertThat(Ranking.compare(key("m100", progress = -1), key("m200", progress = -1, passNo = 1, late = true))).isZero()
    }

    @Test
    fun `빈 입력은 빈 랭킹`() {
        assertThat(Ranking.rank(emptyList())).isEmpty()
        val solo = key("solo", progress = -1)
        assertThat(Ranking.rank(listOf(solo))).containsExactly(RankedKey(solo, 1))
    }

    private fun key(
        login: String,
        progress: Int,
        lastSolvedAtUs: Long? = null,
        passNo: Int? = null,
        late: Boolean = false,
    ) = RankingKey(LoginId(login), cheatFailed = false, progress, lastSolvedAtUs, passNo, late)

    /** I7 — 각 줄 rank = 1 + (나보다 엄격히 위인 줄 수) · 표시 순서에서 앞 줄과 동률이면 rank 같음, 아니면 아이디 순. */
    private fun assertCompetitionRanks(ranked: List<RankedKey>) {
        ranked.forEach { row ->
            val above = ranked.count { Ranking.compare(it.key, row.key) < 0 }
            assertThat(row.rank).isEqualTo(1 + above)
        }
        ranked.zipWithNext { a, b ->
            val c = Ranking.compare(a.key, b.key)
            assertThat(c).isLessThanOrEqualTo(0)
            if (c == 0) assertThat(a.key.loginId.value).isLessThan(b.key.loginId.value)
        }
    }
}
