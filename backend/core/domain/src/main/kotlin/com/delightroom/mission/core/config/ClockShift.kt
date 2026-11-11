package com.delightroom.mission.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * 런타임 시각 이동량(µs — 로컬 데모 시계, prd D25 · LLD platform §4.4). 운영(데모 꺼짐)에서는 언제나 0 이다.
 * [GameProperties.now] · [GameProperties.clockOffsetUs] 가 이 값을 `game.clock-offset` 에 더한다 — 앱의 「지금」과 Lua `offsetUs` 가 같은 값을 본다.
 */
class ClockShift {
    private val state = MutableStateFlow(0L)

    /** 지금 이동량(µs). */
    val micros: Long get() = state.value

    val duration: Duration get() = Duration.of(state.value, ChronoUnit.MICROS)

    fun set(micros: Long) {
        state.value = micros
    }

    /** 이동량이 [seen] 과 달라질 때까지 실제 시간으로 최대 [max] 기다린다. 달라졌으면(이미 달랐어도) true. */
    suspend fun awaitChange(seen: Long, max: Duration): Boolean =
        withTimeoutOrNull(max.toMillis().coerceAtLeast(1)) { state.first { it != seen } } != null
}
