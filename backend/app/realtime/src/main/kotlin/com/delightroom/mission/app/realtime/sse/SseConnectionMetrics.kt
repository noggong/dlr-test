package com.delightroom.mission.app.realtime.sse

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * 게이지 `realtime.connections` — 이 노드에 붙은 SSE 연결 수(LLD realtime-board §4.4 · ORACLE O31).
 * 읽을 때마다 [SseConnectionRegistry] 를 본다. 그룹별 태그는 두지 않는다 — 그룹 수만큼 시계열이 생긴다.
 */
@Component
class SseConnectionMetrics(private val connections: SseConnectionRegistry) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder(NAME, connections) { it.connectionCount().toDouble() }
            .description("이 노드에 붙은 SSE 연결 수")
            .baseUnit("connections")
            .strongReference(true)
            .register(registry)
    }

    companion object {
        const val NAME = "realtime.connections"
    }
}
