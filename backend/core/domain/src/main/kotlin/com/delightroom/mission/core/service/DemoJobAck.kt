package com.delightroom.mission.core.service

import java.time.LocalDate

/**
 * `worker` 잡이 경계의 일을 끝냈다는 표시(SPEC platform P16 · LLD §4.4-5). 데모 시계가 켜진 앱에서는 [DemoClockService.markDone] 으로 이어지고,
 * 꺼진 앱(운영)에서는 [NONE] 이라 아무것도 쓰지 않는다. 빈은 [com.delightroom.mission.core.config.CoreConfiguration] 이 하나 만든다.
 */
fun interface DemoJobAck {
    /** [field](`DemoJob.field`)의 일이 [date] 까지 끝났다. 실패해도 예외를 내지 않는다 — 잡을 막지 않는다. */
    suspend fun done(field: String, date: LocalDate)

    companion object {
        val NONE: DemoJobAck = DemoJobAck { _, _ -> }
    }
}
