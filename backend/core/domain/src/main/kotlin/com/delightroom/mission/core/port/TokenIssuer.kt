package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.auth.AccessClaims
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.IssuedTokens
import com.delightroom.mission.core.domain.auth.RefreshClaims
import java.time.Instant

/** access · refresh 토큰(JWS HS256 — auth-signup LLD §2.3). `now` 는 초 아래를 버린다. */
interface TokenIssuer {
    fun issue(account: Account, jti: String, now: Instant): IssuedTokens

    /** 서명 · `now < exp` · access 모양(`sub` · `lid` · `gid`, `jti` 없음). 하나라도 어기면 `null`. */
    fun verifyAccess(token: String, now: Instant): AccessClaims?

    /** 서명 · `now < exp` · refresh 모양(`sub` · `jti`, `lid` 없음). 하나라도 어기면 `null`. 세션 대조는 하지 않는다. */
    fun verifyRefresh(token: String, now: Instant): RefreshClaims?
}
