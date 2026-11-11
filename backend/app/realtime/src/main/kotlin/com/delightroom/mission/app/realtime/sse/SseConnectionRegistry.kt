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

    /** 등록되지 않은 그룹이면 아무것도 하지 않는다 — 연결 정리(detach)를 수치 어긋남으로 멈추지 않는다. */
    fun unregister(groupId: GroupId) {
        var registered = false
        perGroup.computeIfPresent(groupId) { _, n ->
            registered = true
            (n - 1).takeIf { it > 0 }
        }
        if (registered) total.decrementAndGet()
    }

    fun connectionCount(): Int = total.get()

    fun connectionCount(groupId: GroupId): Int = perGroup[groupId] ?: 0
}
