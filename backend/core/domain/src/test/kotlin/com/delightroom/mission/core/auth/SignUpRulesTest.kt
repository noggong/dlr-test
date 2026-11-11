package com.delightroom.mission.core.auth

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.auth.RawPassword
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.text.Normalizer
import java.util.Locale

/** 가입 입력 규칙 — SPEC auth-signup §3.1 AS1 ~ AS3 · ORACLE O1 ~ O3. */
class SignUpRulesTest {
    private fun assertInvalidRequest(block: () -> Any?) {
        assertThatThrownBy { block() }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Nested
    inner class LoginIdRule {
        @ParameterizedTest(name = "O1 맞는 예 [{0}] 통과")
        @ValueSource(strings = ["alice_01", "user", "a_b_c_d_e_f_g_h_i_j_"])
        fun `O1 맞는 예는 그대로 통과`(raw: String) {
            assertThat(LoginId.parse(raw).value).isEqualTo(raw)
        }

        @ParameterizedTest(name = "O1 틀린 예 [{0}] 거부")
        @ValueSource(strings = ["abc", "Alice", "al ice", "앨리스", "abcdefghijklmnopqrstu"])
        fun `O1 틀린 예는 INVALID_REQUEST`(raw: String) {
            assertInvalidRequest { LoginId.parse(raw) }
        }

        @Test
        fun `O1 경계 - 20자 통과 · 끝 줄바꿈 거부`() {
            assertThat(LoginId.parse("a".repeat(20)).value).hasSize(20)
            assertInvalidRequest { LoginId.parse("alice\n") }
            assertInvalidRequest { LoginId.parse("") }
        }
    }

    @Nested
    inner class RawPasswordRule {
        @ParameterizedTest(name = "O2 맞는 예 [{0}] 통과")
        @ValueSource(strings = ["correct-horse-9", "12345678"])
        fun `O2 맞는 예는 통과`(raw: String) {
            assertThat(RawPassword(raw).value).isEqualTo(raw)
        }

        @Test
        fun `O2 길이 - 8자 · 64자 통과, 7자 · 65자 · 빈 문자열 거부`() {
            assertThat(RawPassword("a".repeat(8)).value).hasSize(8)
            assertThat(RawPassword("a".repeat(64)).value).hasSize(64)
            assertInvalidRequest { RawPassword("a".repeat(7)) }
            assertInvalidRequest { RawPassword("a".repeat(65)) }
            assertInvalidRequest { RawPassword("") }
        }

        @Test
        fun `O2 출력 가능한 ASCII 경계 - 0x21 과 0x7E 통과`() {
            assertThat(RawPassword("!!!!~~~~").value).isEqualTo("!!!!~~~~")
        }

        @ParameterizedTest(name = "O2 ASCII 밖 [{0}] 거부")
        @ValueSource(strings = ["pass word", "password\t", "비밀번호비밀번호", "passwörd1", "password\u007F"])
        fun `O2 공백 · ASCII 밖 문자는 INVALID_REQUEST`(raw: String) {
            assertInvalidRequest { RawPassword(raw) }
        }

        @Test
        fun `O2 toString 은 평문을 드러내지 않는다`() {
            val password = RawPassword("correct-horse-9")
            assertThat(password.toString()).isEqualTo("***")
            assertThat("$password").doesNotContain("correct-horse-9")
        }
    }

    @Nested
    inner class GroupNameRule {
        @Test
        fun `O3 맞는 예 - Team-A 아침반 · 앞뒤 공백 제거 · 7시_모임`() {
            assertThat(GroupName.parse("Team-A 아침반").value).isEqualTo("Team-A 아침반")
            assertThat(GroupName.parse("  ab ").value).isEqualTo("ab")
            assertThat(GroupName.parse("7시_모임").value).isEqualTo("7시_모임")
        }

        @ParameterizedTest(name = "O3 틀린 예 [{0}] 거부")
        @ValueSource(strings = ["a", "   ", "팀!", "ㄱㄴ", "팀🙂", "abcdefghijklmnopqrstu"])
        fun `O3 틀린 예는 INVALID_REQUEST`(raw: String) {
            assertInvalidRequest { GroupName.parse(raw) }
        }

        @ParameterizedTest(name = "O3 엣지 [{0}] 거부")
        @ValueSource(strings = ["가\u0301", "a\uD83D", "\u00A0ab", "ab\u00A0"])
        fun `O3 결합 문자 · 짝 없는 서로게이트 · 앞뒤 NBSP 는 INVALID_REQUEST`(raw: String) {
            // NBSP 는 Character.isWhitespace 가 아니라 strip 되지 않는다 — Kotlin trim() 으로 바꾸면 여기서 잡힌다
            assertInvalidRequest { GroupName.parse(raw) }
        }

        @Test
        fun `O3 앞뒤 줄바꿈 · 전각 공백은 strip 된다`() {
            assertThat(GroupName.parse("\nab\n").value).isEqualTo("ab")
            assertThat(GroupName.parse("\u3000ab\u3000").value).isEqualTo("ab")
        }

        @Test
        fun `O3 정확히 20자 통과 · 한글 20자 통과`() {
            assertThat(GroupName.parse("a".repeat(20)).value).hasSize(20)
            assertThat(GroupName.parse("가".repeat(20)).value).isEqualTo("가".repeat(20))
            assertInvalidRequest { GroupName.parse("가".repeat(21)) }
        }

        @Test
        fun `O3 NFD 로 넣은 팀원은 NFC 로 통과`() {
            val nfd = Normalizer.normalize("팀원", Normalizer.Form.NFD)
            assertThat(nfd).hasSize(6) // 팀 = ㅌ ㅣ ㅁ, 원 = ㅇ ㅝ ㄴ — NFC 2자와 다르다
            assertThat(GroupName.parse(nfd).value).isEqualTo("팀원")
        }

        @Test
        fun `O3 가운데 공백은 그대로, 앞뒤 공백은 길이에 세지 않는다`() {
            assertThat(GroupName.parse("  a  b  ").value).isEqualTo("a  b")
            assertThat(GroupName.parse(" " + "a".repeat(20) + "\t").value).hasSize(20)
        }

        @ParameterizedTest(name = "O3 허용 밖 공백 [{0}] 거부")
        @ValueSource(strings = ["a\tb", "a b", "a　b"])
        fun `O3 가운데 공백은 U+0020 만 허용`(raw: String) {
            assertInvalidRequest { GroupName.parse(raw) }
        }

        @Test
        fun `O3 key 는 영문만 소문자로 바꾼다`() {
            assertThat(GroupName.parse("Team-A 아침반").key).isEqualTo("team-a 아침반")
            assertThat(GroupName.parse("TEAM-A").key).isEqualTo(GroupName.parse("team-a").key)
        }

        @Test
        fun `O3 key 는 기본 로캘이 터키어여도 Locale ROOT 로 바꾼다`() {
            val original = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr"))
                // tr 로캘의 lowercase() 는 I 를 점 없는 ı(U+0131)로 바꾼다
                assertThat(GroupName.parse("TITLE").key).isEqualTo("title")
            } finally {
                Locale.setDefault(original)
            }
        }
    }
}
