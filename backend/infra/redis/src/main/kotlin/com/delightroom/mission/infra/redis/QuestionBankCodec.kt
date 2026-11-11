package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionBank
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * 은행 키 값 JSON `{"1":[<questions.id>…],"2":[…],"3":[…]}`(LLD game-session §2.3) ↔ [QuestionBank].
 * 배열 순서 = 응답 순서라 그대로 둔다.
 */
class QuestionBankCodec {
    private val json: JsonMapper = JsonMapper.builder().build()

    fun encode(bank: QuestionBank): String =
        buildString(bank.idsByDifficulty.values.sumOf { it.size } * 8 + 24) {
            append('{')
            Difficulty.entries.forEachIndexed { i, d ->
                if (i > 0) append(',')
                append('"').append(d.level).append("\":[")
                bank.forDifficulty(d).joinTo(this, ",")
                append(']')
            }
            append('}')
        }

    /** 모양이 틀리면 [IllegalArgumentException]. */
    fun decode(raw: String): QuestionBank {
        val root = try {
            json.readTree(raw)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("은행 JSON 을 읽지 못함", e)
        }
        require(root.isObject) { "은행 값이 JSON 객체가 아님" }
        val ids = Difficulty.entries.associateWith { d ->
            val node = root.get(d.level.toString())
            require(node != null && node.isArray) { "은행에 난이도 ${d.level} 배열 없음" }
            List(node.size()) { i ->
                val id = node.get(i)
                require(id.isIntegralNumber) { "은행 ID 가 정수가 아님: $id" }
                id.longValue()
            }
        }
        return QuestionBank(ids)
    }
}
