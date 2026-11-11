package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * core 포트의 PostgreSQL 구현을 등록한다. 구현 클래스는 internal.
 * 접근은 R2DBC — [DatabaseClient] · [TransactionalOperator] 는 Spring Boot 자동 구성이 만든다.
 */
@AutoConfiguration
class RdbInfraAutoConfiguration {
    @Bean
    fun userRepository(
        databaseClient: DatabaseClient,
        transactionalOperator: TransactionalOperator,
    ): UserRepository = RdbUserRepository(databaseClient, transactionalOperator)

    @Bean
    fun groupRepository(): GroupRepository = RdbGroupRepository()

    @Bean
    fun questionRepository(): QuestionRepository = RdbQuestionRepository()

    @Bean
    fun gameResultRepository(databaseClient: DatabaseClient): GameResultRepository = RdbGameResultRepository(databaseClient)
}
