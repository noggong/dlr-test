package com.delightroom.mission.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 로컬 데모 시계 · 시각 이동 API(prd D25 · D26 · SPEC platform §6.1). 앱 기본은 꺼짐(운영) — compose 만 켠다. */
@ConfigurationProperties("game.demo")
data class DemoClockProperties(
    val enabled: Boolean = false,
    /** 첫 기동 → 게임 07:00 의 실제 시간(SPEC P10). */
    val lead: Duration = Duration.ofMinutes(3),
    /** 세 앱이 Redis `demo:clock` 을 다시 읽는 주기(SPEC P11). */
    val syncInterval: Duration = Duration.ofSeconds(1),
    /** 한 번에 옮길 수 있는 최대 폭(게임 시각 — SPEC P12). */
    val maxSpan: Duration = Duration.ofDays(7),
    /** 건너뛴 경계 하나에서 `worker` 의 완료 표시를 기다리는 최대 실제 시간(SPEC P17). */
    val jobWait: Duration = Duration.ofSeconds(60),
) {
    init {
        require(!lead.isNegative && lead <= Duration.ofHours(1)) { "game.demo.lead 는 0 ~ 1시간이다: $lead" }
        require(syncInterval > Duration.ZERO) { "game.demo.sync-interval 은 0 보다 크다: $syncInterval" }
        require(maxSpan >= Duration.ofDays(1)) { "game.demo.max-span 은 하루 이상이다(to:start 는 언제나 하루 안): $maxSpan" }
        require(jobWait > Duration.ZERO) { "game.demo.job-wait 은 0 보다 크다: $jobWait" }
    }
}
