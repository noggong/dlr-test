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
    // 그룹 Hash 의 ranking JSON 읽기(GroupDaySnapshot)
    implementation(libs.jackson.module.kotlin)

    // 설정 바인딩 테스트가 앱과 같이 Kotlin 주 생성자로 묶도록(앱 모듈은 kotlin-reflect 를 갖는다)
    testRuntimeOnly(libs.kotlin.reflect)

    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(libs.spring.context)
    testFixturesApi(libs.archunit.junit5)
    testFixturesImplementation(libs.kotlin.metadata.jvm)
    testFixturesImplementation(libs.snakeyaml)
    testFixturesImplementation(libs.jackson.module.kotlin)
}
