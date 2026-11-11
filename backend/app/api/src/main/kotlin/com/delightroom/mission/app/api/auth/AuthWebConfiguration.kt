package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.config.DemoClockProperties
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.port.PasswordHasher
import com.delightroom.mission.core.port.TokenIssuer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import java.time.Clock

/**
 * 인증 빈(auth-signup LLD §1). [AuthProperties] 는 `api` 에서만 켠다 — `auth.jwt-secret` 이 없거나 32바이트 미만이면
 * 이 앱의 기동이 실패한다. 인증 필터 · `@CurrentUser` 리졸버(T-PF-4)도 여기 둔다 — `realtime` 도 core 를 스캔하므로
 * core 에 두면 JWT 키가 없는 `realtime` 기동이 깨진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class AuthWebConfiguration : WebFluxConfigurer {
    @Bean
    fun passwordHasher(properties: AuthProperties): PasswordHasher = BcryptPasswordHasher(properties.bcryptCost)

    @Bean
    fun tokenIssuer(properties: AuthProperties): TokenIssuer = JwtTokenIssuer(properties)

    @Bean
    fun authCookies(properties: AuthProperties): AuthCookies = AuthCookies(properties)

    @Bean
    fun accessTokenWebFilter(
        tokenIssuer: TokenIssuer,
        gameProperties: GameProperties,
        clock: Clock,
        demo: DemoClockProperties,
    ): AccessTokenWebFilter = AccessTokenWebFilter(tokenIssuer, gameProperties, clock, demo.enabled)

    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(CurrentUserArgumentResolver())
    }
}
