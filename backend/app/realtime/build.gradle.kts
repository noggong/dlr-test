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
}

tasks.test {
    systemProperty("openapi.path", rootProject.file("../docs/api/openapi.yaml").absolutePath)
}
