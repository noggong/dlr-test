package com.delightroom.mission.app.worker.prepare

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `worker: prepare-questions` 설정(LLD game-session §3.6). 실행 시각은 `game.prepare-times`. */
@ConfigurationProperties("worker.prepare-questions")
data class PrepareQuestionsProperties(
    /** false 면 실행 시각을 기다리는 루프를 띄우지 않는다(잡 본문을 직접 부르는 테스트). */
    val enabled: Boolean = true,
    /** 한 번에 읽는 유저 수(Q5 `LIMIT` · Q3 · 파이프라인 한 묶음). */
    val batchSize: Int = 1_000,
    /** 묶음 · 대상 읽기 실패 뒤 첫 재시도까지(그 다음은 두 배). */
    val retryBackoff: Duration = Duration.ofSeconds(1),
) {
    init {
        require(!retryBackoff.isNegative) { "worker.prepare-questions.retry-backoff 는 0 이상이다: $retryBackoff" }
        require(batchSize in 1..10_000) { "worker.prepare-questions.batch-size 는 1 ~ 10,000 이다: $batchSize" }
    }
}
