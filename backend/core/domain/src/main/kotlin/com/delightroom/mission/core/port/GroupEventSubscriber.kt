package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.event.GroupEvent
import com.delightroom.mission.core.domain.group.GroupId
import kotlinx.coroutines.flow.Flow

/** 한 그룹의 이벤트를 구독한다 — 실시간 앱의 팬아웃 원천. */
interface GroupEventSubscriber {
    fun subscribe(groupId: GroupId): Flow<GroupEvent>
}
