package com.delightroom.mission.common.answer

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.io.File

/** ORACLE platform O34 — [AnswerNormalizer] 가 공유 벡터(`docs/api/answer-normalization-vectors.json`)와 같다. */
class AnswerNormalizerTest {
    private val doc: Any = run {
        val path = requireNotNull(System.getProperty("answerVectors.path")) { "answerVectors.path 시스템 속성이 없다" }
        JsonPath.parse(File(path).readText(Charsets.UTF_8)).json()
    }

    private fun <T> read(path: String): T = JsonPath.read(doc, path)

    @Test
    fun `O34 공유 벡터의 모든 줄이 normalize · hash 와 같다`() {
        val questionId: String = read("$.questionId")
        val vectors: List<Map<String, String>> = read("$.vectors")

        assertThat(vectors).hasSize(35)
        assertSoftly { soft ->
            vectors.forEach { v ->
                val name = v.getValue("name")
                soft.assertThat(AnswerNormalizer.normalize(v.getValue("input"))).describedAs(name).isEqualTo(v.getValue("normalized"))
                soft.assertThat(AnswerNormalizer.hash(questionId, v.getValue("input"))).describedAs(name).isEqualTo(v.getValue("hash"))
            }
        }
    }

    @Test
    fun `O34 seedQuestions 의 정규화 · 해시가 같다`() {
        val seeds: List<Map<String, Any>> = read("$.seedQuestions")

        assertThat(seeds).hasSize(4)
        seeds.forEach { q ->
            val answer = q.getValue("answer") as String
            assertThat(AnswerNormalizer.normalize(answer)).isEqualTo(q.getValue("normalized"))
            assertThat(AnswerNormalizer.hash(q.getValue("questionId") as String, answer)).isEqualTo(q.getValue("answerHash"))
        }
    }

    @Test
    fun `O34 지우는 문자 목록이 벡터 파일의 removed 와 같다`() {
        val removed: List<String> = read("$.removed")

        assertThat(AnswerNormalizer.REMOVED.map { "U+%04X".format(it.code) }).containsExactlyInAnyOrderElementsOf(removed).hasSize(33)
    }
}
