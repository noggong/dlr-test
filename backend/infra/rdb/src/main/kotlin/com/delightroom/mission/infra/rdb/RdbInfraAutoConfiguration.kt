package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.DeadLetterStore
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.SignalEventRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
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
    fun groupRepository(databaseClient: DatabaseClient): GroupRepository = RdbGroupRepository(databaseClient)

    /** 문제 캐시가 기동 때 이 저장소로 Q1 을 읽는다 — schema.sql · data.sql(문제 시드) 적용 뒤에 만든다. */
    @Bean
    @DependsOnDatabaseInitialization
    fun questionRepository(databaseClient: DatabaseClient): QuestionRepository = RdbQuestionRepository(databaseClient)

    @Bean
    fun dailyProgressStore(databaseClient: DatabaseClient): DailyProgressStore = RdbDailyProgressStore(databaseClient)

    @Bean
    fun gameResultRepository(databaseClient: DatabaseClient): GameResultRepository = RdbGameResultRepository(databaseClient)

    /** `worker: persister` 의 solve-signal 처리기가 쓴다(LLD solve-signal §2.3). */
    @Bean
    fun signalEventRepository(
        databaseClient: DatabaseClient,
        transactionalOperator: TransactionalOperator,
    ): SignalEventRepository = RdbSignalEventRepository(databaseClient, transactionalOperator)

    /** `worker: persister` 가 처리하지 못한 메시지를 쌓는다(prd D20 · LLD platform §3.1 dead-letter). */
    @Bean
    fun deadLetterStore(databaseClient: DatabaseClient): DeadLetterStore = RdbDeadLetterStore(databaseClient)
}
