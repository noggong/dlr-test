package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.service.DeadlineService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** [DeadlineService] 는 worker 만 쓰므로 core 가 아니라 여기서 빈으로 올린다(LLD deadline-result §1). */
@Configuration(proxyBeanMethods = false)
class DeadlineConfiguration {
    @Bean
    fun deadlineService(groups: GroupDayRepository, game: GameProperties, properties: DeadlineProperties): DeadlineService =
        DeadlineService(groups, game, properties.options)
}
