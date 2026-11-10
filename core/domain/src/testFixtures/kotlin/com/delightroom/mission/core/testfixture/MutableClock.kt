package com.delightroom.mission.core.testfixture

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 테스트에서 시각을 옮길 수 있는 [Clock]. */
class MutableClock(
    private var current: Instant,
    private val zone: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
