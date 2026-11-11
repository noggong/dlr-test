package com.delightroom.mission.app.worker.persister

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `worker: persister` 소비 루프 설정(LLD platform §3.1). 인스턴스를 여럿 띄울 때만 [instanceIndex] · [instanceCount] 를 준다. */
@ConfigurationProperties("worker.persister")
data class PersisterProperties(
    /** false 면 루프를 띄우지 않는다(잡만 시험할 때). */
    val enabled: Boolean = true,
    /** 이 인스턴스 번호 — 버킷 `b mod instanceCount == instanceIndex` 를 맡고, 소비자 이름은 `worker-<instanceIndex>`. */
    val instanceIndex: Int = 0,
    val instanceCount: Int = 1,
    /** 레인(코루틴) 수 — 버킷 하나는 레인 하나만 돈다. */
    val parallelism: Int = 16,
    /** 한 번에 읽는 메시지 수(`COUNT` · 1 ~ 1,000). */
    val batchSize: Int = 100,
    /** 한 바퀴 동안 읽은 것이 없을 때 쉬는 시간. */
    val idleDelay: Duration = Duration.ofMillis(200),
    /** `XAUTOCLAIM` 간격 — 기동 직후 한 번, 그 뒤 이 간격마다. */
    val claimInterval: Duration = Duration.ofSeconds(30),
    /** 이보다 오래 ACK 되지 않은 메시지를 회수한다. */
    val claimMinIdle: Duration = Duration.ofSeconds(30),
    /** 처리기 · Redis 실패 뒤 그 버킷을 다시 읽기까지 — 실패가 이어지면 두 배씩 [retryBackoffMax] 까지. */
    val retryBackoff: Duration = Duration.ofSeconds(1),
    val retryBackoffMax: Duration = Duration.ofSeconds(30),
) {
    init {
        require(instanceCount >= 1) { "worker.persister.instance-count 는 1 이상이다: $instanceCount" }
        require(instanceIndex in 0 until instanceCount) {
            "worker.persister.instance-index 는 0 ~ ${instanceCount - 1} 이다: $instanceIndex"
        }
        require(parallelism >= 1) { "worker.persister.parallelism 은 1 이상이다: $parallelism" }
        // persister_ack 가 id 를 unpack 한다 — Lua 의 unpack 한도(약 8,000) 아래로
        require(batchSize in 1..1_000) { "worker.persister.batch-size 는 1 ~ 1,000 이다: $batchSize" }
        require(!retryBackoff.isNegative && retryBackoffMax >= retryBackoff) {
            "worker.persister.retry-backoff 는 0 이상 · retry-backoff-max 이하다"
        }
    }

    /** 소비자 이름 — 재기동해도 같아 자기 PEL 을 다시 읽는다. */
    val consumerName: String get() = "worker-$instanceIndex"
}
