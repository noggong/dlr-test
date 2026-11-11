// REST API 앱
plugins {
    id("mission.spring-boot-app")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":infra:redis"))
    implementation(project(":infra:rdb"))
    implementation(libs.spring.security.crypto)
    implementation(libs.nimbus.jose.jwt)

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.snakeyaml)
    testImplementation(libs.testcontainers)
}

tasks.test {
    systemProperty("openapi.path", rootProject.file("../docs/api/openapi.yaml").absolutePath)
    // compose 의 로컬 기본값과 같은 문구(SPEC platform §2) — 테스트 컨텍스트가 AUTH_JWT_SECRET 없이 뜨게 한다
    environment("AUTH_JWT_SECRET", "mission-local-jwt-secret-not-for-production")
}
