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
    // 계약 파일이 바뀌면 테스트를 다시 돌린다 — 시스템 속성 경로만으로는 Gradle 이 입력으로 보지 않아 UP-TO-DATE 로 건너뛴다(ORACLE realtime-board O30)
    inputs.file(rootProject.file("../docs/api/openapi.yaml")).withPropertyName("openapi").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.file("../docs/api/asyncapi.yaml")).withPropertyName("asyncapi").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("openapi.path", rootProject.file("../docs/api/openapi.yaml").absolutePath)
    systemProperty("asyncapi.path", rootProject.file("../docs/api/asyncapi.yaml").absolutePath)
}
