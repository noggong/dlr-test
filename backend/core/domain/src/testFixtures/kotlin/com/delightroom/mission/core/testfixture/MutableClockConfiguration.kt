package com.delightroom.mission.core.testfixture

import com.delightroom.mission.core.config.GameProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.OffsetDateTime

/**
 * `@Import(MutableClockConfiguration::class)` 로 운영 Clock 을 [MutableClock] 으로 바꾼다.
 * 컴포넌트 스캔에 걸리지 않도록 @Configuration 을 붙이지 않는다.
 */
class MutableClockConfiguration {
    @Bean
    @Primary
    fun mutableClock(properties: GameProperties): MutableClock =
        MutableClock(OffsetDateTime.parse(DEFAULT_NOW).toInstant(), properties.zone)

    companion object {
        const val DEFAULT_NOW = "2026-11-11T07:00:00+09:00"
    }
}
