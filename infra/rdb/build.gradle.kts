// 보관 저장소(PostgreSQL): 유저 · 그룹 · 문제 · 결과
// 접근 방식(R2DBC / JDBC)은 아직 정하지 않아 드라이버 의존을 넣지 않는다
plugins {
    id("mission.spring-library")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.spring.boot.autoconfigure)
}
