package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/** REST 의 Bearer 토큰. 형식(JWT · 불투명 토큰)은 auth-signup 설계에서 정한다. */
class AccessToken(
    val value: String,
    val expiresAt: Instant,
)

/** SSE 연결용 단기 1회용 티켓 — EventSource 는 Authorization 헤더를 못 보낸다. */
class RealtimeTicket(
    val value: String,
    val userId: UserId,
    val expiresAt: Instant,
) {
    fun isValidAt(now: Instant): Boolean = throw NotImplementedYet("realtime-board")
}
