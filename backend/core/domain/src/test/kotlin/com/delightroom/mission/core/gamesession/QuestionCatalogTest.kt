package com.delightroom.mission.core.gamesession

import com.delightroom.mission.core.domain.question.Category
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.Question
import com.delightroom.mission.core.domain.question.QuestionCatalog
import com.delightroom.mission.core.domain.question.QuestionId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 문제 캐시(LLD game-session §2.3) — 은행 뽑기 입력 · 응답의 PK 찾기. */
class QuestionCatalogTest {
    private fun question(id: Long, difficulty: Int) =
        Question(id, QuestionId("%064x".format(id)), Difficulty.of(difficulty), Category.GENERAL, "본문 $id", "%064x".format(id + 1000))

    @Test
    fun `O13 (바탕) 내부 ID · PK 로 찾고 난이도마다 ID 오름차순`() {
        val catalog = QuestionCatalog(listOf(question(5, 1), question(2, 1), question(9, 3)))

        assertThat(catalog.size).isEqualTo(3)
        assertThat(catalog.byId.getValue(2).id).isEqualTo(QuestionId("%064x".format(2)))
        assertThat(catalog.byPk.getValue(QuestionId("%064x".format(9))).internalId).isEqualTo(9)
        assertThat(catalog.idsByDifficulty.getValue(Difficulty.ONE)).containsExactly(2L, 5L)
        assertThat(catalog.idsByDifficulty.getValue(Difficulty.TWO)).isEmpty()
        assertThat(catalog.idsByDifficulty.getValue(Difficulty.THREE)).containsExactly(9L)
    }

    @Test
    fun `O13 (바탕) 같은 내부 ID 나 PK 가 둘이면 적재 실패`() {
        assertThatThrownBy { QuestionCatalog(listOf(question(1, 1), question(1, 2))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val dupPk = Question(2, QuestionId("%064x".format(1)), Difficulty.ONE, Category.GENERAL, "", "")
        assertThatThrownBy { QuestionCatalog(listOf(question(1, 1), dupPk)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `O13 (바탕) 난이도는 1 · 2 · 3 만`() {
        assertThat(Difficulty.of(3)).isEqualTo(Difficulty.THREE)
        assertThatThrownBy { Difficulty.of(4) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
