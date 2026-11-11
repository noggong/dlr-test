package com.delightroom.mission.app.api

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 JVM 하나에 Redis 컨테이너 하나(`redis:8-alpine` — compose 와 같은 판).
 * 처음 쓸 때 띄우고, JVM 이 끝나면 Testcontainers(Ryuk)가 내린다.
 *
 * `@ContextConfiguration(initializers = [RedisTestContainer.Initializer::class])` 로 `spring.data.redis.*` 를 이 컨테이너로 돌린다.
 */
object RedisTestContainer {
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).also { it.start() }
    }

    class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            TestPropertyValues.of(
                "spring.data.redis.host=${container.host}",
                "spring.data.redis.port=${container.getMappedPort(6379)}",
            ).applyTo(context.environment)
        }
    }
}
