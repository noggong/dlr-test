package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.auth.AccessToken
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock

/** 가입할 때 고르는 그룹(R-G1). */
sealed interface GroupChoice {
    val name: GroupName

    data class New(override val name: GroupName) : GroupChoice

    data class Existing(override val name: GroupName) : GroupChoice
}

/** auth-signup — 가입 · 그룹 선택/생성 · 로그인(FR-2 · FR-8 · §5.1). */
@Service
class AuthService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val clock: Clock,
) {
    suspend fun signUp(loginId: LoginId, rawPassword: String, group: GroupChoice): User =
        // TODO(auth-signup): 그룹명 유일 · 정원 · 비밀번호 해시
        throw NotImplementedYet("auth-signup")

    suspend fun login(loginId: LoginId, rawPassword: String): AccessToken =
        // TODO(auth-signup): 자격 증명 확인 · 토큰 발급
        throw NotImplementedYet("auth-signup")
}
