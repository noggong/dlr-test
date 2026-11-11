package com.delightroom.mission.app.realtime.sse

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 요청이 어떻게 끝나든(본문을 다 씀 · 클라이언트 끊김 · 본문을 읽기 전 실패) 그 요청이 붙인 [GroupChannelConnection] 을 허브에 돌려준다.
 * 컨트롤러는 스트림을 열기 전에 구독을 붙이는데(RB5), 스트림 `finally` 는 본문을 수집해야 돈다 — 수집 전에 응답이 취소되면 연결이 남아
 * 그룹 구독이 풀리지 않는다. [GroupChannelHub.detach] 는 두 번 불러도 한 번이라 스트림 `finally` 와 겹쳐도 된다.
 */
@Component
class SseConnectionReleaseFilter(private val hub: GroupChannelHub) : WebFilter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("sse-release"))

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        chain.filter(exchange).doFinally {
            val connection = exchange.getAttribute<GroupChannelConnection>(ATTRIBUTE) ?: return@doFinally
            scope.launch { hub.detach(connection) }
        }

    companion object {
        /** 컨트롤러가 attach 한 연결을 이 이름으로 요청에 남긴다. */
        val ATTRIBUTE: String = SseConnectionReleaseFilter::class.java.name + ".connection"
    }
}
