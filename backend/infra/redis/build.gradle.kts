// 핫 경로 저장소: 진행도 · 그룹 이벤트 Pub/Sub · 실시간 티켓
plugins {
    id("mission.spring-library")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.kotlinx.coroutines.reactor)
    // 그룹 채널 메시지 JSON(GroupChannelCodec)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.testcontainers)
    // 랭킹 경계 표 · 무작위 그룹 — Kotlin 테스트와 같은 입력(ORACLE deadline-result O2 · O3)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.jackson.module.kotlin)
}
