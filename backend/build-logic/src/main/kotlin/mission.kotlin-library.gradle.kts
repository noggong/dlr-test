// 모든 모듈의 공통 설정: Kotlin JVM · Java 21 · Spring Boot BOM · JUnit
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<LibrariesForLibs>()

group = "com.delightroom.mission"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test)
    // Gradle 9 는 launcher 를 넣어 주지 않는다. 앱 모듈은 Spring Boot 플러그인이 넣지만 라이브러리 모듈(core · infra)은 없다
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
