package com.delightroom.mission.core.domain.auth

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/** 로그인 · refresh 가 읽는 한 줄(users ⋈ groups — auth-signup LLD §4 Q5 · Q6). */
data class Account(
    val userId: UserId,
    val loginId: LoginId,
    val passwordHash: String,
    val groupId: GroupId,
    val groupName: GroupName,
) {
    override fun toString(): String = "Account(userId=$userId, loginId=$loginId, groupId=$groupId, groupName=$groupName)"
}

/** 가입 결과 — `groupName` 은 저장된 `groups.name` 이다(LLD §3.2). */
data class SignedUp(
    val loginId: LoginId,
    val groupName: GroupName,
    val joinedAt: Instant,
)

/** 로그인 · refresh 결과(LLD §3.3 · §3.4) — 컨트롤러가 [tokens] 를 쿠키 2개로, 나머지를 `SessionResponse` 로 옮긴다. */
class IssuedSession(
    val loginId: LoginId,
    val groupName: GroupName,
    val tokens: IssuedTokens,
) {
    override fun toString(): String = "IssuedSession(loginId=$loginId, groupName=$groupName, tokens=$tokens)"
}

/** `GET /api/v1/auth/me` 결과(LLD §3.7) — 비밀번호 해시를 컨트롤러로 넘기지 않으려고 [Account] 와 따로 둔다. */
data class Me(
    val loginId: LoginId,
    val groupName: GroupName,
)
