package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/**
 * 가입 · 로그인 요청의 평문 비밀번호(SPEC AS2 · prd D19). 8~64자, 출력 가능한 ASCII(`0x21`~`0x7E`)만.
 * ASCII 만이라 64자 = 64바이트로 BCrypt 입력 한도(72바이트) 안이다.
 * [toString] 은 `***` — 로그 · 예외 메시지에 평문이 실리지 않게 한다.
 */
@JvmInline
value class RawPassword(val value: String) {
    init {
        if (value.length !in MIN_LENGTH..MAX_LENGTH || !value.all { it.code in 0x21..0x7E }) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "password: 8~64자, 공백 없는 영문 · 숫자 · 특수문자")
        }
    }

    override fun toString(): String = "***"

    private companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 64
    }
}

/** access token 을 검증해 얻은 것(auth-signup LLD §2.3) — 인증 필터가 `AuthPrincipal` 로 옮긴다. */
data class AccessClaims(
    val userId: UserId,
    val loginId: LoginId,
    val groupId: GroupId,
    val expiresAt: Instant,
)

/** refresh token 을 검증해 얻은 것 — [jti] 를 `sess:{<uid>}` 값과 비교한다(LLD §2.2). */
data class RefreshClaims(
    val userId: UserId,
    val jti: String,
    val expiresAt: Instant,
)

/** 한 번에 발급한 두 토큰과 만료 시각 — 응답 본문 · 쿠키 `Expires` 가 같은 값을 쓴다. */
class IssuedTokens(
    val access: String,
    val accessExpiresAt: Instant,
    val refresh: String,
    val refreshExpiresAt: Instant,
) {
    override fun toString(): String = "IssuedTokens(accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt)"
}

/** SSE 연결용 30초 1회용 티켓 — EventSource 는 헤더 · 다른 출처 쿠키를 못 싣는다. */
class RealtimeTicket(
    val value: String,
    val userId: UserId,
    val expiresAt: Instant,
) {
    fun isValidAt(now: Instant): Boolean = throw NotImplementedYet("realtime-board")
}
