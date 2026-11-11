package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId

/** 검사를 마친 가입 입력(SPEC auth-signup AS1 ~ AS4). */
class SignUpForm private constructor(
    val loginId: LoginId,
    val password: RawPassword,
    val group: GroupChoice,
) {
    companion object {
        /**
         * 세 필드를 모두 검사하고, 하나라도 어기면 어긴 필드의 메시지를 모아 `INVALID_REQUEST` 하나로 던진다(LLD §3.1).
         * 메시지에는 필드 이름과 규칙만 들어간다 — 입력값(특히 비밀번호)은 넣지 않는다.
         */
        fun parse(loginId: String, password: String, mode: GroupChoice.Mode, groupName: String): SignUpForm {
            val violations = mutableListOf<String>()

            fun <T> check(parse: () -> T): T? =
                try {
                    parse()
                } catch (e: BusinessException) {
                    violations += e.message
                    null
                }

            val id = check { LoginId.parse(loginId) }
            val pw = check { RawPassword(password) }
            val name = check { GroupName.parse(groupName) }
            if (id == null || pw == null || name == null) {
                throw BusinessException(ErrorCode.INVALID_REQUEST, violations.joinToString(" / "))
            }
            return SignUpForm(id, pw, GroupChoice.of(mode, name))
        }
    }
}
