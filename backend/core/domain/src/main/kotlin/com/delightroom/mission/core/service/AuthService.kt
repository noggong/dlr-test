package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.IssuedSession
import com.delightroom.mission.core.domain.auth.RawPassword
import com.delightroom.mission.core.domain.auth.SignUpForm
import com.delightroom.mission.core.domain.auth.SignedUp
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.PasswordHasher
import com.delightroom.mission.core.port.RefreshTokenRepository
import com.delightroom.mission.core.port.TokenIssuer
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * auth-signup — 가입 · 로그인 · refresh · 로그아웃(FR-2 · FR-8 · §5.1).
 * Spring 트랜잭션을 쓰지 않는다 — 가입 트랜잭션은 [UserRepository.signUp] 안에 있다(LLD auth-signup §1).
 */
@Service
class AuthService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val refreshTokens: RefreshTokenRepository,
    private val tokenIssuer: TokenIssuer,
    private val authProperties: AuthProperties,
    private val gameProperties: GameProperties,
    private val clock: Clock,
) {
    /**
     * 가입(LLD §3.2) — 입력 규칙 → 해시(트랜잭션 밖 — DB 잠금을 쥔 채 해시하지 않는다) → 한 트랜잭션.
     * 토큰은 주지 않는다(SPEC AS7).
     */
    suspend fun signUp(loginId: String, password: String, groupMode: GroupChoice.Mode, groupName: String): SignedUp {
        val form = SignUpForm.parse(loginId, password, groupMode, groupName)
        val hash = passwordHasher.hash(form.password)
        return users.signUp(form.loginId, hash, form.group, MAX_GROUP_SIZE)
    }

    /**
     * 로그인(LLD §3.3) — 형식 검사를 하지 않는다. 형식 밖 아이디 · 비밀번호는 「없는 아이디」와 같은 길(더미 비교 뒤
     * `INVALID_CREDENTIALS`)이다. 자격 증명이 맞을 때만 세션을 연다(`SET NX` — 살아 있으면 `ALREADY_LOGGED_IN`).
     */
    suspend fun login(loginId: String, password: String): IssuedSession {
        val account = parseOrNull { LoginId.parse(loginId) }?.let { users.findAccountByLoginId(it) }
        val raw = parseOrNull { RawPassword(password) }
        if (account == null || raw == null) {
            passwordHasher.dummyMatch()
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        if (!passwordHasher.matches(raw, account.passwordHash)) throw BusinessException(ErrorCode.INVALID_CREDENTIALS)

        val now = now()
        val jti = newJti()
        // 서명보다 먼저 — 여기서 지면 아무것도 발급하지 않는다(SPEC AS9 · ORACLE I4)
        if (!refreshTokens.start(account.userId, jti, authProperties.refreshTtl)) {
            throw BusinessException(ErrorCode.ALREADY_LOGGED_IN)
        }
        return issue(account, jti, now)
    }

    /**
     * refresh(LLD §3.4) — 서명 · 만료 · 모양을 본 뒤 `sess:{<uid>}` 가 이 토큰의 `jti` 일 때만 새 `jti` 로 회전한다.
     * 어느 단계든 실패는 `UNAUTHORIZED` 하나(컨트롤러는 쿠키를 건드리지 않는다 — SPEC AS12).
     */
    suspend fun refresh(refreshToken: String?): IssuedSession {
        val now = now()
        val claims = refreshToken?.takeIf { it.isNotEmpty() }?.let { tokenIssuer.verifyRefresh(it, now) }
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val account = users.findAccountById(claims.userId) ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val jti = newJti()
        if (!refreshTokens.rotate(claims.userId, claims.jti, jti, authProperties.refreshTtl)) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        return issue(account, jti, now)
    }

    /**
     * 로그아웃(LLD §3.5) — [userId] 는 검증된 access token 의 주인이다. 실린 refresh token 이 유효하고 같은 유저일 때만
     * 세션을 끝낸다(`sess_end.lua` 가 `jti` 를 대조). 그 밖은 Redis 를 건드리지 않는다 — 다른 기기의 로그인을 끊지 않는다.
     */
    suspend fun logout(userId: UserId, refreshToken: String?) {
        val claims = refreshToken?.takeIf { it.isNotEmpty() }?.let { tokenIssuer.verifyRefresh(it, now()) } ?: return
        if (claims.userId != userId) return
        refreshTokens.end(userId, claims.jti)
    }

    private fun issue(account: Account, jti: String, now: Instant): IssuedSession =
        IssuedSession(account.loginId, account.groupName, tokenIssuer.issue(account, jti, now))

    /** 토큰 `iat` 는 초 단위(LLD §2.3). */
    private fun now(): Instant = gameProperties.now(clock).truncatedTo(ChronoUnit.SECONDS)

    private fun newJti(): String = UUID.randomUUID().toString()

    /** 입력 규칙 위반(`INVALID_REQUEST`)만 `null` 로 — 로그인은 400 을 내지 않는다(LLD §3.3). */
    private inline fun <T> parseOrNull(block: () -> T): T? =
        try {
            block()
        } catch (e: BusinessException) {
            if (e.errorCode != ErrorCode.INVALID_REQUEST) throw e
            null
        }

    private companion object {
        /** 정원 = 전체 소속 인원 10명(R-G3 · prd D12). */
        const val MAX_GROUP_SIZE = 10
    }
}
