package com.delightroom.mission.common.answer

import java.security.MessageDigest
import java.text.Normalizer

/**
 * 정답 정규화 · 해시 — prd R-A2 · D22 · D7. 클라이언트가 같은 규칙으로 판정하므로 결과는
 * 공유 벡터 `docs/api/answer-normalization-vectors.json` 과 같아야 한다(ORACLE platform O34).
 */
object AnswerNormalizer {
    /** D22 — 공백으로 보고 지우는 문자 33개. 모두 BMP 라 `Char` 하나씩 견준다. */
    val REMOVED: Set<Char> = buildSet {
        // Unicode White_Space 속성 전부(25)
        addAll(listOf('\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0', '\u1680'))
        addAll('\u2000'..'\u200A')
        addAll(listOf('\u2028', '\u2029', '\u202F', '\u205F', '\u3000'))
        // 폭 0 문자(5) · 예전 공백 U+180E · 한글 채움 문자(2)
        addAll(listOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF', '\u180E', '\u3164', '\uFFA0'))
    }

    /** ① [REMOVED] 전부 제거 → ② NFC → ③ A-Z 만 a-z(로캘 소문자를 쓰지 않는다 — `İ` 등은 그대로). */
    fun normalize(answer: String): String {
        val nfc = Normalizer.normalize(answer.filterNot { it in REMOVED }, Normalizer.Form.NFC)
        return buildString(nfc.length) { nfc.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) } }
    }

    /** `SHA-256(UTF-8("<questionId>:<normalize(answer)>"))` 소문자 hex 64자(prd D7). */
    fun hash(questionId: String, answer: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$questionId:${normalize(answer)}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
