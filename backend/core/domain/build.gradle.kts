// 도메인 모델 · 서비스 · 포트(인터페이스). 구현은 infra 에 둔다
plugins {
    id("mission.spring-library")
    `java-test-fixtures`
}

dependencies {
    api(project(":support:common"))
    implementation(libs.spring.context)
    implementation(libs.spring.boot.autoconfigure)
    api(libs.kotlinx.coroutines.core)

    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(libs.spring.context)
    testFixturesApi(libs.archunit.junit5)
    testFixturesImplementation(libs.kotlin.metadata.jvm)
    testFixturesImplementation(libs.snakeyaml)
    testFixturesImplementation(libs.jackson.module.kotlin)
}
