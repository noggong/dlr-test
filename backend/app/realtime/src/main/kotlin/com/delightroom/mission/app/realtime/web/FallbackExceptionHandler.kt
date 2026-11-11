package com.delightroom.mission.app.realtime.web

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.FailureClassifier
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

/**
 * 컨트롤러의 [GlobalExceptionHandler] 가 잡지 않은 예외와 WebFilter 예외를 `{ code, message }` 로 바꾼다(prd D21 · LLD platform §4.1).
 * 의존 서비스 장애는 503 `UNAVAILABLE`, 그 밖은 500 `INTERNAL_ERROR` 이고 `message` 는 고정 문장이다 — 원인은 ERROR 로그에만 남긴다.
 * Boot 기본 처리기(`@Order(-1)`)보다 먼저 돈다. 이미 시작된 응답과 Spring 이 상태를 정한 요청 오류(404 · 405 등)는 넘긴다.
 */
@Component
@Order(-2)
class FallbackExceptionHandler(private val json: JsonMapper) : WebExceptionHandler {
    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted || ex is ResponseStatusException) return Mono.error(ex)

        val code = FailureClassifier.errorCodeOf(ex)
        val message = if (ex is BusinessException) ex.message else code.defaultMessage
        if (ex !is BusinessException) {
            // 경로만 남긴다 — 쿼리(SSE 티켓)는 로그에 싣지 않는다
            log.error("http.{} {} {}", code.name.lowercase(), exchange.request.method, exchange.request.path.value(), ex)
        }
        response.statusCode = HttpStatus.valueOf(code.httpStatus)
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = json.writeValueAsBytes(ErrorResponse(code.name, message))
        return response.writeWith(Mono.fromSupplier { response.bufferFactory().wrap(body) })
    }

    private companion object {
        val log = LoggerFactory.getLogger(FallbackExceptionHandler::class.java)
    }
}
