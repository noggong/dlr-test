package com.delightroom.mission.app.realtime.realtimeboard

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * realtime 앱 테스트 JVM 하나에 Redis 컨테이너 하나(`redis:8-alpine` — compose 와 같은 판).
 * 처음 쓸 때 띄우고, JVM 이 끝나면 Testcontainers(Ryuk)가 내린다.
 */
object RealtimeTestRedis {
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).also { it.start() }
    }

    /** `@DynamicPropertySource` 에서 부른다 — 앱의 Redis 연결을 이 컨테이너로 돌린다. */
    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.data.redis.host") { container.host }
        registry.add("spring.data.redis.port") { container.getMappedPort(6379) }
    }
}
