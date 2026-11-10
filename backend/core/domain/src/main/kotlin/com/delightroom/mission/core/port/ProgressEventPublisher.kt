package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.event.GroupEvent

/** 그룹 이벤트를 실시간 앱으로 내보낸다. */
interface ProgressEventPublisher {
    suspend fun publish(event: GroupEvent)
}
