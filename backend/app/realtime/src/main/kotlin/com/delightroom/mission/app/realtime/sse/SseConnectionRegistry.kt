package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import org.springframework.stereotype.Component

/** 이 인스턴스에 붙은 SSE 연결을 그룹 단위로 관리한다. 그룹 채널 구독은 그룹의 첫 연결에 열고 마지막 연결에 닫는다. */
@Component
class SseConnectionRegistry {
    fun register(groupId: GroupId, userId: UserId): Unit = throw NotImplementedYet("realtime-board")

    fun unregister(groupId: GroupId, userId: UserId): Unit = throw NotImplementedYet("realtime-board")

    fun connectionCount(): Int = throw NotImplementedYet("realtime-board")
}
