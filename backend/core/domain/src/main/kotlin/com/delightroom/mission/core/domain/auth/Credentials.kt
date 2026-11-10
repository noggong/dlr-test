package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/** access token — HttpOnly 쿠키 `access` 로만 오간다(JWS HS256 · 1시간 — auth-signup SPEC §2). */
class AccessToken(
    val value: String,
    val expiresAt: Instant,
)

/** SSE 연결용 30초 1회용 티켓 — EventSource 는 헤더 · 다른 출처 쿠키를 못 싣는다. */
class RealtimeTicket(
    val value: String,
    val userId: UserId,
    val expiresAt: Instant,
) {
    fun isValidAt(now: Instant): Boolean = throw NotImplementedYet("realtime-board")
}
