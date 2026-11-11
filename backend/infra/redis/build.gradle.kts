// 핫 경로 저장소: 진행도 · 그룹 이벤트 Pub/Sub · 실시간 티켓
plugins {
    id("mission.spring-library")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation(libs.testcontainers)
}
