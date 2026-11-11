package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.port.PasswordHasher
import com.delightroom.mission.core.port.TokenIssuer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 인증 빈(auth-signup LLD §1). [AuthProperties] 는 `api` 에서만 켠다 — `auth.jwt-secret` 이 없거나 32바이트 미만이면
 * 이 앱의 기동이 실패한다. 인증 필터 · `@CurrentUser` 리졸버는 T-PF-4 가 더한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class AuthWebConfiguration {
    @Bean
    fun passwordHasher(properties: AuthProperties): PasswordHasher = BcryptPasswordHasher(properties.bcryptCost)

    @Bean
    fun tokenIssuer(properties: AuthProperties): TokenIssuer = JwtTokenIssuer(properties)

    @Bean
    fun authCookies(properties: AuthProperties): AuthCookies = AuthCookies(properties)
}
