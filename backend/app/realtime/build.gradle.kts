// 실시간(SSE) 앱: 연결 관리 · Redis Pub/Sub 구독 팬아웃
plugins {
    id("mission.spring-boot-app")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":infra:redis"))
    implementation(project(":infra:rdb"))

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.snakeyaml)
    // 그룹 채널 구독 공유 테스트(ORACLE realtime-board I3 · I4) — Redis 컨테이너에 직접 SPUBLISH · PUBSUB SHARDNUMSUB
    testImplementation(libs.testcontainers)
    testImplementation(libs.spring.boot.starter.data.redis.reactive)
}

tasks.test {
    systemProperty("openapi.path", rootProject.file("../docs/api/openapi.yaml").absolutePath)
    systemProperty("asyncapi.path", rootProject.file("../docs/api/asyncapi.yaml").absolutePath)
}
