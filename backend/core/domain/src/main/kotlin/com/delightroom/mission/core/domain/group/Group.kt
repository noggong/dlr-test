package com.delightroom.mission.core.domain.group

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.user.User
import java.text.Normalizer
import java.util.Locale

@JvmInline
value class GroupId(val value: Long)

/**
 * 유일한 그룹명(R-G2). 비교 키 = 앞뒤 공백 제거 + 영문 소문자(prd D11).
 *
 * 생성자는 저장소에서 읽은 값을 그대로 감싼다. 바깥 입력은 [parse] 로 검사한다.
 */
@JvmInline
value class GroupName(val value: String) {
    /** 유일성 비교 키(`groups.name_key`) — ASCII 영문만 소문자로 바꾼다. 한글은 바뀌지 않는다. */
    val key: String get() = value.lowercase(Locale.ROOT)

    companion object {
        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 20

        /** SPEC AS3 — NFC 정규화 → 앞뒤 공백 제거(`strip`) → 코드 포인트 2~20 → 허용 문자 검사. */
        fun parse(raw: String): GroupName {
            // Java String.strip 과 같은 기준(Character.isWhitespace). Kotlin trim() 은 NBSP 까지 지운다
            val name = Normalizer.normalize(raw, Normalizer.Form.NFC).trim { Character.isWhitespace(it) }
            val length = name.codePointCount(0, name.length)
            if (length !in MIN_LENGTH..MAX_LENGTH || !name.codePoints().allMatch(::isAllowed)) {
                throw BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "group.name: 2~20자, 한글 · 영문 · 숫자 · 공백 · - · _",
                )
            }
            return GroupName(name)
        }

        /** 한글 완성형 음절(U+AC00~U+D7A3) · ASCII 영문 · 숫자 · 공백(U+0020) · `-` · `_`. */
        private fun isAllowed(cp: Int): Boolean =
            cp in 0xAC00..0xD7A3 ||
                cp in 'a'.code..'z'.code ||
                cp in 'A'.code..'Z'.code ||
                cp in '0'.code..'9'.code ||
                cp == ' '.code || cp == '-'.code || cp == '_'.code
    }
}

class Group(
    val id: GroupId,
    val name: GroupName,
    val members: List<User>,
) {
    /** 정원(최대 10명) 안에서 새 유저를 받을 수 있는가(R-G3). 정원은 전체 소속 인원(prd D12). */
    fun canAccept(maxSize: Int): Boolean = members.size < maxSize
}
