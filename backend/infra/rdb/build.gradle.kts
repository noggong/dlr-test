// 보관 저장소(PostgreSQL): 유저 · 그룹 · 문제 · 결과
// 접근은 R2DBC(DatabaseClient) — 트랜잭션은 TransactionalOperator 로 저장소 안에서 연다(LLD auth-signup §3.2)
plugins {
    id("mission.spring-library")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.kotlinx.coroutines.reactor)
    runtimeOnly(libs.r2dbc.postgresql)
}
