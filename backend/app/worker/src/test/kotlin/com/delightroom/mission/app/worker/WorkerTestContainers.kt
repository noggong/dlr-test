package com.delightroom.mission.app.worker

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 JVM 하나에 Redis · PostgreSQL 컨테이너 하나씩(compose 와 같은 판). 처음 쓸 때 띄우고, JVM 이 끝나면 Testcontainers(Ryuk)가 내린다.
 *
 * `@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])` 로 `spring.data.redis.*` · `spring.r2dbc.*` 를
 * 이 컨테이너로 돌린다. worker 는 스키마를 적용하지 않는다 — 테스트가 쓸 테이블은 테스트가 만든다.
 */
object WorkerTestContainers {
    private val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).also { it.start() }
    }

    private val postgres: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("postgres:18-alpine"))
            .withEnv("POSTGRES_DB", "mission")
            .withEnv("POSTGRES_USER", "mission")
            .withEnv("POSTGRES_PASSWORD", "mission-test")
            .withExposedPorts(5432)
            // initdb 가 임시 서버를 한 번 띄웠다 내리므로 두 번째 「준비됨」까지 기다린다
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .also { it.start() }
    }

    class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            TestPropertyValues.of(
                "spring.data.redis.host=${redis.host}",
                "spring.data.redis.port=${redis.getMappedPort(6379)}",
                "spring.r2dbc.url=r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/mission",
                "spring.r2dbc.username=mission",
                "spring.r2dbc.password=mission-test",
            ).applyTo(context.environment)
        }
    }
}
