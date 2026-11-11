// 배치 · 소비 앱(웹 서버 없음): 잡(member · prepare-questions · deadline) · persister 소비 루프 — LLD platform §1 · §3.1
plugins {
    id("mission.spring-boot-app")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":infra:redis"))
    implementation(project(":infra:rdb"))

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.testcontainers)
    // 시험용 처리기가 PostgreSQL 에 트랜잭션으로 쓰고(커밋 뒤 ACK — ORACLE platform O15 ~ O18), 테스트가 Stream 을 직접 본다
    testImplementation(libs.spring.boot.starter.data.r2dbc)
    testImplementation(libs.spring.boot.starter.data.redis.reactive)
}
