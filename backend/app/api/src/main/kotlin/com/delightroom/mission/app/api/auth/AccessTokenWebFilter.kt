package com.delightroom.mission.app.api.auth

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.port.TokenIssuer
import org.springframework.core.Ordered
import org.springframework.http.server.PathContainer
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import java.time.Clock

/**
 * 인증 필터(auth-signup LLD §3.6 · SPEC AS14).
 *
 * 1. `/api/` 밖(`/health` 등)과 공개 경로 3개(`POST /api/v1/auth/signup` · `login` · `refresh`)는 그대로 통과.
 *    로컬 데모 전용 `/api/v1/dev/` 아래(모든 메서드)도 통과 — 데모 시계가 꺼지면 라우트가 없어 404 다(SPEC platform P9 · P14).
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
        if (!requiresAuth(request.method, request.path.pathWithinApplication())) return chain.filter(exchange)

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

        /**
         * 라우팅과 같은 [PathPattern] 으로 가른다 — 조각마다 퍼센트 디코딩 · `;` 매트릭스 파라미터를 뗀 값으로 맞추므로
         * 원문 문자열 비교로는 빠져나가는 `/%61pi/…` · `/api;x=1/…` 도 보호 경로로 본다.
         */
        private val API: PathPattern = PathPatternParser.defaultInstance.parse("/api/**")
        private val PUBLIC_PATHS: List<PathPattern> =
            listOf("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh").map(PathPatternParser.defaultInstance::parse)

        /** `ErrorResponse` 와 같은 모양 — 코드 · 메시지가 상수라 JSON 을 한 번만 만든다. */
        private val UNAUTHORIZED_BODY: ByteArray =
            """{"code":"${ErrorCode.UNAUTHORIZED.name}","message":"${ErrorCode.UNAUTHORIZED.defaultMessage}"}"""
                .toByteArray(Charsets.UTF_8)

        /** 로컬 데모 전용 오퍼레이션(openapi `x-demo-only`) — 인증 없음(SPEC platform P14). */
        private val DEV: PathPattern = PathPatternParser.defaultInstance.parse("/api/v1/dev/**")

        internal fun requiresAuth(method: HttpMethod, path: PathContainer): Boolean =
            API.matches(path) && !DEV.matches(path) && !(method == HttpMethod.POST && PUBLIC_PATHS.any { it.matches(path) })

        internal fun requiresAuth(method: HttpMethod, path: String): Boolean = requiresAuth(method, PathContainer.parsePath(path))
    }
}
