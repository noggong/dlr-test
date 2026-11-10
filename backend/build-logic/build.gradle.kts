plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    // 컨벤션 플러그인 안에서 타입 안전한 `libs` 접근자를 쓰기 위한 우회
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
