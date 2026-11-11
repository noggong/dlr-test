package com.delightroom.mission.app.realtime.sse

import com.delightroom.mission.core.domain.group.GroupId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 이 노드에 붙은 SSE 연결 수(전체 · 그룹별). [GroupChannelHub] 가 attach · detach 때 올리고 내린다(LLD realtime-board §4.4). */
@Component
class SseConnectionRegistry {
    private val total = AtomicInteger()
    private val perGroup = ConcurrentHashMap<GroupId, Int>()

    fun register(groupId: GroupId) {
        perGroup.merge(groupId, 1, Int::plus)
        total.incrementAndGet()
    }

    fun unregister(groupId: GroupId) {
        perGroup.compute(groupId) { _, n ->
            check(n != null && n > 0) { "등록되지 않은 그룹 연결을 뺀다: $groupId" }
            (n - 1).takeIf { it > 0 }
        }
        total.decrementAndGet()
    }

    fun connectionCount(): Int = total.get()

    fun connectionCount(groupId: GroupId): Int = perGroup[groupId] ?: 0
}
