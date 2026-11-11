package com.delightroom.mission.core.domain.user

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.group.GroupId
import java.time.Instant

@JvmInline
value class UserId(val value: Long)

/**
 * 로그인 ID. 현황판에 그룹원 ID 로 보인다(prd §2 현황판).
 *
 * 생성자는 저장소에서 읽은 값을 그대로 감싼다. 바깥 입력은 [parse] 로 검사한다.
 */
@JvmInline
value class LoginId(val value: String) {
    companion object {
        private val PATTERN = Regex("[a-z0-9_]{4,20}")

        /** SPEC AS1 — 4~20자, 영문 소문자 · 숫자 · `_` 만. 정규화 · 대소문자 변환은 하지 않는다. */
        fun parse(raw: String): LoginId {
            if (!PATTERN.matches(raw)) {
                throw BusinessException(ErrorCode.INVALID_REQUEST, "loginId: 4~20자, 영문 소문자 · 숫자 · _")
            }
            return LoginId(raw)
        }
    }
}

class User(
    val id: UserId,
    val loginId: LoginId,
    val passwordHash: String,
    val groupId: GroupId,
    val joinedAt: Instant,
) {
    /** 그 날짜 참가 기준 시각(`GameClock.joinCutoffAt` — 06:50) 전 가입자만 그날 게임에 참가한다(R-G4 · prd D24). */
    fun isParticipantOn(joinCutoffAt: Instant): Boolean = joinedAt < joinCutoffAt
}
