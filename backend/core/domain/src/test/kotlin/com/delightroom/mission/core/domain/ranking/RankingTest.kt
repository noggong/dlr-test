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
        val logins = ranked.map { it.key.loginId.value }
        val rankOf = ranked.associate { it.key.loginId.value to it.rank }
        assertThat(logins.indexOf("bo_1")).isEqualTo(logins.indexOf("bo11") + 1)
        assertThat(listOf("bo11", "bo_1", "cy_1").map(rankOf::getValue)).containsExactly(2, 2, 4)
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
}
