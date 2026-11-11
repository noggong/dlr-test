package com.delightroom.mission.core.config

import com.delightroom.mission.core.domain.game.GameClock
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 시간은 이 [Clock] 빈으로만 얻는다. 테스트는 MutableClock(testFixtures)으로 바꾼다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GameProperties::class)
class CoreConfiguration {
    @Bean
    fun clock(properties: GameProperties): Clock = Clock.system(properties.zone)

    @Bean
    fun gameClock(properties: GameProperties, clock: Clock): GameClock = GameClock(properties, clock)
}
