package com.delightroom.mission.core.config

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.port.DemoClockStore
import com.delightroom.mission.core.service.DemoClockService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 시간은 이 [Clock] 빈으로만 얻는다. 테스트는 MutableClock(testFixtures)으로 바꾼다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GameProperties::class, DemoClockProperties::class)
class CoreConfiguration {
    @Bean
    fun clock(properties: GameProperties): Clock = Clock.system(properties.zone)

    @Bean
    fun gameClock(properties: GameProperties, clock: Clock): GameClock = GameClock(properties, clock)

    /**
     * 로컬 데모 시계(prd D25 · LLD platform §4.4) — 켜졌을 때만. 빈을 만들 때 첫 이동량을 반영하므로(잡의 `SmartLifecycle.start` 전),
     * 꺼진 앱(운영 기본)은 이 빈이 없고 Redis `demo:clock` 을 읽지 않는다(SPEC P9).
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "game.demo", name = ["enabled"], havingValue = "true")
    fun demoClockService(store: DemoClockStore, properties: GameProperties, demo: DemoClockProperties, clock: Clock): DemoClockService =
        DemoClockService(store, properties, demo, clock).also { it.start() }
}
