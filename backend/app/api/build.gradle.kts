// REST API 앱
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
