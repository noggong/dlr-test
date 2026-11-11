package com.delightroom.mission.core.gamesession

import com.delightroom.mission.core.domain.question.BankDraw
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionBankBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 은행 뽑기(SPEC GS6 · LLD game-session §4.4) 단위. API 수준 판정(O12 · O13)은 T-GS-3 이 한다.
 * 고정 데이터와 같이 난이도마다 30개 — 난이도 1 = 1..30, 2 = 101..130, 3 = 201..230.
 */
class QuestionBankBuilderTest {
    private val catalog: Map<Difficulty, LongArray> = mapOf(
        Difficulty.ONE to (1L..30L).toList().toLongArray(),
        Difficulty.TWO to (101L..130L).toList().toLongArray(),
        Difficulty.THREE to (201L..230L).toList().toLongArray(),
    )

    private fun built(draw: BankDraw) = (draw as BankDraw.Built).bank

    @Test
    fun `O13 출제 이력이 없으면 난이도마다 20개 · 중복 0 · 각 난이도 칸에서만`() {
        val bank = built(QuestionBankBuilder.build(catalog, emptySet(), SIZE))

        assertThat(bank.idsByDifficulty.keys).containsExactly(Difficulty.ONE, Difficulty.TWO, Difficulty.THREE)
        for (d in Difficulty.entries) {
            val ids = bank.forDifficulty(d)
            assertThat(ids).hasSize(20).doesNotHaveDuplicates()
            assertThat(catalog.getValue(d).toList()).containsAll(ids)
        }
    }

    @Test
    fun `O13 모자라면 이력 밖 문제를 전부 준다`() {
        val served = (1L..15L).toSet()

        val bank = built(QuestionBankBuilder.build(catalog, served, SIZE))

        assertThat(bank.forDifficulty(Difficulty.ONE)).containsExactlyInAnyOrderElementsOf(16L..30L)
        assertThat(bank.forDifficulty(Difficulty.TWO)).hasSize(20)
        assertThat(bank.forDifficulty(Difficulty.THREE)).hasSize(20)
    }

    @Test
    fun `O13 남은 문제가 정확히 size 개면 그 전부`() {
        val bank = built(QuestionBankBuilder.build(catalog, (101L..110L).toSet(), SIZE))

        assertThat(bank.forDifficulty(Difficulty.TWO)).containsExactlyInAnyOrderElementsOf(111L..130L)
    }

    @Test
    fun `I5 은행 ∩ 출제 이력 = 0`() {
        val random = Random(42)
        repeat(200) {
            val served = catalog.values.flatMap { it.toList() }.filter { random.nextInt(3) == 0 }.toSet()

            val draw = QuestionBankBuilder.build(catalog, served, SIZE, random)

            val ids = built(draw).idsByDifficulty.values.flatten()
            assertThat(ids).doesNotContainAnyElementsOf(served).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `O12 한 난이도라도 미출제 0개면 고갈`() {
        val served = (201L..230L).toSet()

        assertThat(QuestionBankBuilder.build(catalog, served, SIZE)).isEqualTo(BankDraw.Exhausted(Difficulty.THREE))
    }

    @Test
    fun `O12 고갈 난이도가 여럿이면 낮은 난이도를 알린다 · 문제가 없는 난이도도 고갈`() {
        val served = (1L..30L).toSet() + (201L..230L).toSet()

        assertThat(QuestionBankBuilder.build(catalog, served, SIZE)).isEqualTo(BankDraw.Exhausted(Difficulty.ONE))
        assertThat(QuestionBankBuilder.build(catalog - Difficulty.TWO, emptySet(), SIZE))
            .isEqualTo(BankDraw.Exhausted(Difficulty.TWO))
    }

    @Test
    fun `O13 뽑기는 무작위다 — 같은 입력에 매번 같은 20개가 아니다`() {
        val firsts = (1..20).map {
            built(QuestionBankBuilder.build(catalog, emptySet(), SIZE)).forDifficulty(Difficulty.ONE).toSet()
        }.toSet()
        // 30C20 ≈ 3,000만 가지 — 20번이 모두 같을 확률은 0 에 가깝다
        assertThat(firsts.size).isGreaterThan(1)
    }

    @Test
    fun `O13 고른 20개의 순서도 섞인다 · 모든 후보가 뽑힐 수 있다`() {
        val random = Random(7)
        val seen = HashSet<Long>()
        var sortedCount = 0
        repeat(200) {
            val ids = built(QuestionBankBuilder.build(catalog, emptySet(), SIZE, random)).forDifficulty(Difficulty.ONE)
            seen += ids
            if (ids == ids.sorted()) sortedCount++
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(1L..30L)
        assertThat(sortedCount).isLessThan(5)
    }

    @Test
    fun `O13 (바탕) 입력 문제 캐시 배열을 바꾸지 않는다`() {
        val before = catalog.mapValues { it.value.copyOf() }

        QuestionBankBuilder.build(catalog, setOf(3L, 105L), SIZE)

        for (d in Difficulty.entries) assertThat(catalog.getValue(d)).isEqualTo(before.getValue(d))
    }

    @Test
    fun `O13 (바탕) size 는 1 이상`() {
        assertThatThrownBy { QuestionBankBuilder.build(catalog, emptySet(), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val SIZE = 20
    }
}
