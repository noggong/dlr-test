package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.port.PasswordHasher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 인증 쪽 app 구현 빈(LLD auth-signup §1). */
@Configuration(proxyBeanMethods = false)
class AuthWebConfiguration {
    @Bean
    fun passwordHasher(): PasswordHasher = BcryptPasswordHasher(BCRYPT_COST)

    private companion object {
        /** BCrypt cost 10(LLD auth-signup §2.1 — `$2a$10$…`). T-AU-3 이 `AuthProperties.bcryptCost` 로 옮긴다. */
        const val BCRYPT_COST = 10
    }
}
