// Spring 의존 없는 공통 모듈: 오류 코드 · 비즈니스 예외 · 정답 정규화
plugins {
    id("mission.kotlin-library")
}

tasks.test {
    // ErrorCodeContractTest 가 api-guide §4 표와 enum 을 견준다(ORACLE platform O12) — 표만 바뀌어도 다시 돌도록 입력으로 선언한다
    val apiGuide = rootProject.file("../docs/api-guide.md")
    inputs.file(apiGuide).withPathSensitivity(PathSensitivity.NONE).withPropertyName("apiGuide")
    systemProperty("apiGuide.path", apiGuide.absolutePath)
    // AnswerNormalizerTest 가 클라이언트와 함께 쓰는 정답 정규화 벡터를 읽는다(ORACLE platform O34 · prd D22)
    val answerVectors = rootProject.file("../docs/api/answer-normalization-vectors.json")
    inputs.file(answerVectors).withPathSensitivity(PathSensitivity.NONE).withPropertyName("answerVectors")
    systemProperty("answerVectors.path", answerVectors.absolutePath)
}
