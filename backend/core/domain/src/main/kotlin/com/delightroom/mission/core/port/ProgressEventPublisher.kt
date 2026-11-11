package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.event.GroupChannelMessage

/** 그룹 채널 메시지를 내보낸다. 실제 발행은 신호 Lua 안의 `SPUBLISH` 라 기능 코드는 쓰지 않는다(LLD realtime-board §1). */
interface ProgressEventPublisher {
    suspend fun publish(message: GroupChannelMessage)
}
