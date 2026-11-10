// Spring 의존 없는 공통 모듈: 오류 코드 · 비즈니스 예외
plugins {
    id("mission.kotlin-library")
}

tasks.test {
    // ErrorCodeContractTest 가 api-guide §4 표와 enum 을 견준다(ORACLE platform O12)
    systemProperty("apiGuide.path", rootProject.file("../docs/api-guide.md").absolutePath)
}
