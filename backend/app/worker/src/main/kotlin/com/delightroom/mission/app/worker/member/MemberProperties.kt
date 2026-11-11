package com.delightroom.mission.app.worker.member

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `worker: member` 설정(LLD game-session §3.5). 실행 시각은 `game.start-time`(07:00). */
@ConfigurationProperties("worker.member")
data class MemberProperties(
    /** false 면 실행 시각을 기다리는 루프를 띄우지 않는다(잡 본문을 직접 부르는 테스트). */
    val enabled: Boolean = true,
    /** 한 번에 읽는 그룹 수(Q7 `LIMIT` · Q4 · Q10 한 묶음). */
    val pageSize: Int = 500,
    /** 한 페이지 안에서 동시에 보내는 `EVALSHA roster` 수 — 한 연결 위에서 파이프라인으로 나간다. */
    val concurrency: Int = 64,
    /** 실패 뒤 첫 재시도까지(그 다음은 두 배). */
    val retryBackoff: Duration = Duration.ofSeconds(1),
    /** 다음 07:00 을 기다리는 동안 버킷 표시를 세는 주기(LLD platform §4.1 감시). */
    val watchInterval: Duration = Duration.ofSeconds(10),
) {
    init {
        require(pageSize in 1..10_000) { "worker.member.page-size 는 1 ~ 10,000 이다: $pageSize" }
        require(concurrency in 1..1_024) { "worker.member.concurrency 는 1 ~ 1,024 이다: $concurrency" }
        require(!retryBackoff.isNegative) { "worker.member.retry-backoff 는 0 이상이다: $retryBackoff" }
        require(watchInterval > Duration.ZERO) { "worker.member.watch-interval 은 0 보다 크다: $watchInterval" }
    }
}
