// 실행 가능한 앱 모듈(app/*): bootJar 를 만든다
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("mission.spring-library")
    id("org.springframework.boot")
}

val libs = the<LibrariesForLibs>()

dependencies {
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.spring.boot.starter.webflux.test)
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}
