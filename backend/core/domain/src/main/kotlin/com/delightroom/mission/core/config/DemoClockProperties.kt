package com.delightroom.mission.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 로컬 데모 시계 · 시각 이동 API(prd D25 · SPEC platform §6.1). 앱 기본은 꺼짐(운영) — compose 만 켠다. */
@ConfigurationProperties("game.demo")
data class DemoClockProperties(
    val enabled: Boolean = false,
    /** 첫 기동 → 게임 07:00 의 실제 시간(SPEC P10). */
    val lead: Duration = Duration.ofMinutes(3),
    /** 세 앱이 Redis `demo:clock` 을 다시 읽는 주기(SPEC P11). */
    val syncInterval: Duration = Duration.ofSeconds(1),
    /** 시각 이동이 다음 경계(07:00 · 08:00 · 08:00:03)에 다가갈 수 있는 한계(SPEC P12). */
    val moveMargin: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!lead.isNegative && lead <= Duration.ofHours(1)) { "game.demo.lead 는 0 ~ 1시간이다: $lead" }
        require(syncInterval > Duration.ZERO) { "game.demo.sync-interval 은 0 보다 크다: $syncInterval" }
        require(moveMargin > syncInterval) { "game.demo.move-margin 은 sync-interval($syncInterval)보다 커야 한다: $moveMargin" }
    }
}
