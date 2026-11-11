package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.core.service.DeadlineService
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `worker: deadline` 설정(LLD deadline-result §3.2). 인스턴스를 여럿 띄울 때만 [instanceIndex] · [instanceCount] 를 준다. */
@ConfigurationProperties("worker.deadline")
data class DeadlineProperties(
    /** false 면 스케줄러를 띄우지 않는다(다른 잡만 시험할 때). */
    val enabled: Boolean = true,
    /** 이 인스턴스 번호 — 버킷 `b mod instanceCount == instanceIndex` 를 맡는다. */
    val instanceIndex: Int = 0,
    val instanceCount: Int = 1,
    /** 버킷을 함께 도는 코루틴 수. */
    val bucketParallelism: Int = 32,
    /** 동시에 보내 둔 Lua `deadline` 호출 수(단계 전체). */
    val inflight: Int = 256,
    /** 오류 난 그룹 · `SSCAN` 이 실패한 버킷을 다시 하는 횟수 — 대기는 [retryBackoff] 부터 두 배씩. */
    val retry: Int = 3,
    val retryBackoff: Duration = Duration.ofMillis(100),
    /** Lua 가 `EARLY`(워커 시계가 Redis 보다 빠름)면 이만큼 뒤 그 그룹만 다시, 최대 [earlyRetryMax] 번. */
    val earlyRetryDelay: Duration = Duration.ofMillis(20),
    val earlyRetryMax: Int = 200,
    /** 2차 보고에 실패가 있으면 이만큼 뒤 2차를 다시 — 두 배씩 [phaseRetryBackoffMax] 까지, 다음 날 07:00 전까지(LLD §3.2 8). */
    val phaseRetryBackoff: Duration = Duration.ofSeconds(5),
    val phaseRetryBackoffMax: Duration = Duration.ofMinutes(5),
) {
    init {
        require(phaseRetryBackoff > Duration.ZERO && phaseRetryBackoffMax >= phaseRetryBackoff) {
            "worker.deadline.phase-retry-backoff 는 0 보다 크고 phase-retry-backoff-max 이하다"
        }
    }

    /** 검사는 [DeadlineService.Options] 가 한다 — 바인딩(기동) 때 실패한다. */
    val options: DeadlineService.Options =
        DeadlineService.Options(
            instanceIndex = instanceIndex,
            instanceCount = instanceCount,
            bucketParallelism = bucketParallelism,
            inflight = inflight,
            retry = retry,
            retryBackoff = retryBackoff,
            earlyRetryDelay = earlyRetryDelay,
            earlyRetryMax = earlyRetryMax,
        )
}
