package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.user.UserId
import java.time.Duration

/**
 * 유저마다 살아 있는 refresh token 하나(`sess:{<uid>}` = 그 `jti` — auth-signup LLD §2.2).
 * 세 동작 모두 키 하나에 원자적이다.
 */
interface RefreshTokenRepository {
    /** 로그인 — 세션이 없을 때만 [jti] 로 연다(`SET NX EX`). 이미 있으면 `false`. */
    suspend fun start(userId: UserId, jti: String, ttl: Duration): Boolean

    /** refresh — 지금 값이 [oldJti] 일 때만 [newJti] 로 바꾸고 TTL 을 새로 건다. 아니면(회전됨 · 로그아웃 · 만료) `false`. */
    suspend fun rotate(userId: UserId, oldJti: String, newJti: String, ttl: Duration): Boolean

    /** 로그아웃 — 지금 값이 [jti] 일 때만 지운다. 아니면 `false`(그대로 둔다). */
    suspend fun end(userId: UserId, jti: String): Boolean
}
