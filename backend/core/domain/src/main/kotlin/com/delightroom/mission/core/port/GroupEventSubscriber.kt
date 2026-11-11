package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.event.GroupChannelLostException
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.group.GroupId
import kotlinx.coroutines.flow.Flow

/** 한 그룹의 채널 메시지를 구독한다 — 실시간 앱의 팬아웃 원천(LLD realtime-board §1 · §4.4). */
interface GroupEventSubscriber {
    /**
     * 수집을 시작하면 그룹 채널을 구독하고, Redis 의 구독 확인을 받은 뒤 [onSubscribed] 를 한 번 부른다.
     * 그 뒤 온 메시지만 흘린다. 수집을 취소하면 구독 해제 확인까지 기다린 뒤 끝난다.
     * 구독 연결이 끊기면 [GroupChannelLostException] 으로 끝난다(끊긴 사이 메시지를 잃었을 수 있다).
     * 한 노드에서 같은 그룹을 동시에 두 번 구독하지 않는다 — 어기면 [IllegalStateException].
     */
    fun subscribe(groupId: GroupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage>
}
