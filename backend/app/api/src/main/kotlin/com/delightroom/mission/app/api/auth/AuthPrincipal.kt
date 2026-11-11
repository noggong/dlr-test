package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId

/**
 * 인증된 요청의 주인(auth-signup LLD §3.6) — [AccessTokenWebFilter] 가 access 토큰 클레임에서 만들어
 * exchange 속성 [ATTRIBUTE] 에 싣는다. 저장소를 보지 않으므로 토큰이 살아 있는 동안의 값이다.
 */
data class AuthPrincipal(
    val userId: UserId,
    val loginId: LoginId,
    val groupId: GroupId,
) {
    companion object {
        const val ATTRIBUTE = "auth.principal"
    }
}

/**
 * 컨트롤러 파라미터에 [AuthPrincipal] 을 넣는다 — `suspend fun x(@CurrentUser user: AuthPrincipal)`.
 * 공개 경로(가입 · 로그인 · refresh)에서는 쓰지 않는다 — 필터를 거치지 않아 401 이 된다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class CurrentUser
