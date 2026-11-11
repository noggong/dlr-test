package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.auth.AccessToken
import com.delightroom.mission.core.domain.auth.SignUpForm
import com.delightroom.mission.core.domain.auth.SignedUp
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.port.PasswordHasher
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * auth-signup — 가입 · 로그인(FR-2 · FR-8 · §5.1).
 * Spring 트랜잭션을 쓰지 않는다 — 가입 트랜잭션은 [UserRepository.signUp] 안에 있다(LLD auth-signup §1).
 */
@Service
class AuthService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
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

    suspend fun login(loginId: LoginId, rawPassword: String): AccessToken =
        // TODO(auth-signup): 자격 증명 확인 · 토큰 발급
        throw NotImplementedYet("auth-signup")

    private companion object {
        /** 정원 = 전체 소속 인원 10명(R-G3 · prd D12). */
        const val MAX_GROUP_SIZE = 10
    }
}
