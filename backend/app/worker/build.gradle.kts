// 배치 · 소비 앱(웹 서버 없음): 잡(member · prepare-questions · deadline) · persister 소비 루프 — LLD platform §1 · §3.1
plugins {
    id("mission.spring-boot-app")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":infra:redis"))
    implementation(project(":infra:rdb"))

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.testcontainers)
    // 시험용 처리기가 PostgreSQL 에 트랜잭션으로 쓰고(커밋 뒤 ACK — ORACLE platform O15 ~ O18), 테스트가 Stream 을 직접 본다
    testImplementation(libs.spring.boot.starter.data.r2dbc)
    testImplementation(libs.spring.boot.starter.data.redis.reactive)
}

// 부하 · 메모리 테스트(ORACLE game-session O32 ~ O35)는 `-Dgame.load=true` 일 때만 돈다 — 그 속성을 테스트 JVM 으로 넘긴다.
// 그때는 `*LoadTest` 만 돌리고(다른 컨텍스트의 PostgreSQL 호출이 측정에 섞이지 않게) 캐시 · up-to-date 없이 매번 잰다
tasks.test {
    providers.systemProperty("game.load").orNull?.let { load ->
        systemProperty("game.load", load)
        filter.includeTestsMatching("com.delightroom.mission.app.worker.gamesession.*LoadTest")
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
