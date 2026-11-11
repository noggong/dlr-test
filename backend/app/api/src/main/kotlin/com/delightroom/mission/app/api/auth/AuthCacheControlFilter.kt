package com.delightroom.mission.app.api.auth

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

/**
 * `/api/v1/auth/` 아래 모든 응답(오류 포함)에 `Cache-Control: no-store`(SPEC auth-signup AS17).
 * 컨트롤러가 아니라 필터에서 붙여야 디코딩 오류 · 415 처럼 컨트롤러에 닿지 않는 응답에도 실린다.
 * 경로는 라우팅 · [AccessTokenWebFilter] 와 같은 [PathPattern] 으로 맞춘다 — 원문 접두 비교는 라우팅이 디코딩해
 * 컨트롤러에 닿는 `/api/v1/%61uth/login` · `/api/v1/auth;x=1/login` 을 놓친다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthCacheControlFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (AUTH_PATHS.matches(exchange.request.path.pathWithinApplication())) {
            exchange.response.headers.setCacheControl(NO_STORE)
        }
        return chain.filter(exchange)
    }

    private companion object {
        val AUTH_PATHS: PathPattern = PathPatternParser.defaultInstance.parse("/api/v1/auth/**")
        const val NO_STORE = "no-store"
    }
}
