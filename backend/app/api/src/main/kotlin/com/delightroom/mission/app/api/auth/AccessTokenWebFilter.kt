package com.delightroom.mission.app.api.auth

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.port.TokenIssuer
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Clock

/**
 * 인증 필터(auth-signup LLD §3.6 · SPEC AS14).
 *
 * 1. `/api/` 밖(`/health` 등)과 공개 경로 3개(`POST /api/v1/auth/signup` · `login` · `refresh`)는 그대로 통과.
 * 2. `access` 쿠키를 [TokenIssuer.verifyAccess] 로 검증 — 쿠키 없음 · 위조 · 만료 · refresh 토큰을 넣음은 모두
 *    401 `UNAUTHORIZED` 본문을 여기서 직접 쓴다(컨트롤러까지 가지 않는다). `Authorization` 헤더는 보지 않는다.
 * 3. 통과하면 [AuthPrincipal] 을 exchange 속성에 싣는다. 저장소(Redis · PostgreSQL)는 보지 않는다 — 07:00 몰림(HLD §1).
 */
class AccessTokenWebFilter(
    private val tokenIssuer: TokenIssuer,
    private val gameProperties: GameProperties,
    private val clock: Clock,
) : WebFilter, Ordered {
    override fun getOrder(): Int = ORDER

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        if (!requiresAuth(request.method, request.path.pathWithinApplication().value())) return chain.filter(exchange)

        val token = request.cookies.getFirst(AuthCookies.ACCESS)?.value
        val claims = token?.takeIf { it.isNotEmpty() }?.let { tokenIssuer.verifyAccess(it, gameProperties.now(clock)) }
            ?: return unauthorized(exchange)

        exchange.attributes[AuthPrincipal.ATTRIBUTE] = AuthPrincipal(claims.userId, claims.loginId, claims.groupId)
        return chain.filter(exchange)
    }

    private fun unauthorized(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.valueOf(ErrorCode.UNAUTHORIZED.httpStatus)
        response.headers.contentType = MediaType.APPLICATION_JSON
        return response.writeWith(Mono.fromSupplier { response.bufferFactory().wrap(UNAUTHORIZED_BODY) })
    }

    companion object {
        /** Spring Security 를 쓰지 않으므로 다른 필터보다 앞에 둔다. */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 10

        private const val API_PREFIX = "/api/"
        private val PUBLIC_PATHS = setOf("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh")

        /** `ErrorResponse` 와 같은 모양 — 코드 · 메시지가 상수라 JSON 을 한 번만 만든다. */
        private val UNAUTHORIZED_BODY: ByteArray =
            """{"code":"${ErrorCode.UNAUTHORIZED.name}","message":"${ErrorCode.UNAUTHORIZED.defaultMessage}"}"""
                .toByteArray(Charsets.UTF_8)

        internal fun requiresAuth(method: HttpMethod, path: String): Boolean =
            path.startsWith(API_PREFIX) && !(method == HttpMethod.POST && path in PUBLIC_PATHS)
    }
}
