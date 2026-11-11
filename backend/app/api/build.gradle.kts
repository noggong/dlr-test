// REST API 앱
plugins {
    id("mission.spring-boot-app")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":infra:redis"))
    implementation(project(":infra:rdb"))
    implementation(libs.spring.security.crypto)

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.snakeyaml)
    testImplementation(libs.testcontainers)
    testImplementation(libs.spring.boot.starter.data.r2dbc) // 테스트가 DatabaseClient 로 저장된 값을 확인한다
}

tasks.test {
    systemProperty("openapi.path", rootProject.file("../docs/api/openapi.yaml").absolutePath)
}
