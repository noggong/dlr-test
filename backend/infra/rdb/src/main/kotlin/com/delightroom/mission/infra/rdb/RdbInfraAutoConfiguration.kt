package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * core 포트의 PostgreSQL 구현을 등록한다. 구현 클래스는 internal.
 * 접근 방식(R2DBC / JDBC)이 정해지면 커넥션 빈을 받도록 바꾼다.
 */
@AutoConfiguration
class RdbInfraAutoConfiguration {
    @Bean
    fun userRepository(): UserRepository = RdbUserRepository()

    @Bean
    fun groupRepository(): GroupRepository = RdbGroupRepository()

    @Bean
    fun questionRepository(): QuestionRepository = RdbQuestionRepository()

    @Bean
    fun gameResultRepository(): GameResultRepository = RdbGameResultRepository()
}
