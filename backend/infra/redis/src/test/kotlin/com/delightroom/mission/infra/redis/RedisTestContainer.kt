package com.delightroom.mission.infra.redis

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 JVM 하나에 Redis 컨테이너 하나(`redis:8-alpine` — compose 와 같은 판).
 * 처음 쓸 때 띄우고, JVM 이 끝나면 Testcontainers(Ryuk)가 내린다.
 */
object RedisTestContainer {
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).also { it.start() }
    }

    val template: ReactiveStringRedisTemplate by lazy {
        val factory = LettuceConnectionFactory(container.host, container.getMappedPort(6379))
        factory.afterPropertiesSet()
        factory.start()
        ReactiveStringRedisTemplate(factory)
    }
}
