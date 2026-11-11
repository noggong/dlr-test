package com.delightroom.mission.app.api

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 JVM 하나에 PostgreSQL 컨테이너 하나(`postgres:18-alpine` — compose 와 같은 판).
 * 처음 쓸 때 띄우고, JVM 이 끝나면 Testcontainers(Ryuk)가 내린다. 스키마는 앱 기동이 schema.sql 로 만든다.
 *
 * `@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])` 로 `spring.r2dbc.*` 를 이 컨테이너로 돌린다.
 */
object PostgresTestContainer {
    private const val DB = "mission"
    private const val USER = "mission"
    private const val PASSWORD = "mission-test"

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("postgres:18-alpine"))
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            // 스프링 테스트 컨텍스트 캐시가 컨텍스트마다 커넥션 풀을 쥔다 — 기본 100 이면 테스트 클래스가 늘 때 `too many clients`
            .withCommand("postgres", "-c", "max_connections=500")
            // initdb 가 임시 서버를 한 번 띄웠다 내리므로 두 번째 「준비됨」까지 기다린다
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .also { it.start() }
    }

    class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            TestPropertyValues.of(
                "spring.r2dbc.url=r2dbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$DB",
                "spring.r2dbc.username=$USER",
                "spring.r2dbc.password=$PASSWORD",
            ).applyTo(context.environment)
        }
    }
}
