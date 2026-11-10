package com.delightroom.mission.core.domain.ranking

import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.testfixture.RankingCases
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random

/** ORACLE deadline-result O1 · O4(Kotlin) — 경계 사례 표 `ranking-cases.json`. */
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
        val window = ranked.map { it.key.loginId.value to it.rank }.subList(1, 4)
        assertThat(window).containsExactly("bo11" to 2, "bo_1" to 2, "cy_1" to 4)
    }

    @Test
    fun `compare 는 반대칭이다`() {
        val keys = RankingCases.groups.flatMap { it.members }.map(RankingCases::key)
        for (a in keys) for (b in keys) {
            assertThat(Integer.signum(Ranking.compare(a, b))).isEqualTo(-Integer.signum(Ranking.compare(b, a)))
        }
    }

    @Test
    fun `빈 입력은 빈 랭킹`() {
        assertThat(Ranking.rank(emptyList())).isEmpty()
        val solo = RankingKey(LoginId("solo"), false, -1, null, null, false)
        assertThat(Ranking.rank(listOf(solo))).containsExactly(RankedKey(solo, 1))
    }
}
