package com.delightroom.mission.common.error

/**
 * 컨트롤러가 처리하지 못한 예외를 응답 코드로 나눈다(prd D21 · LLD platform §4.1).
 *
 * 예외 자신과 원인 사슬의 어느 하나가 의존 서비스 장애면 [ErrorCode.UNAVAILABLE](503 — 클라이언트가 재시도), 아니면
 * [ErrorCode.INTERNAL_ERROR](500 — 서버 결함). 이 모듈은 Spring · 드라이버에 기대지 않으므로 외부 타입은 상위 클래스까지 이름으로 본다.
 */
object FailureClassifier {
    /** LLD platform §4.1 표 — 이 타입이거나 그 하위 타입이면 의존 서비스 장애다. */
    private val UNAVAILABLE_TYPES = setOf(
        "org.springframework.dao.DataAccessResourceFailureException", // RedisConnectionFailureException · R2DBC 연결 획득 실패
        "org.springframework.dao.TransientDataAccessException", // QueryTimeoutException(Redis 명령 시간 초과) · R2DBC 일시 오류
        "io.lettuce.core.RedisConnectionException",
        "io.lettuce.core.RedisCommandTimeoutException",
        "io.r2dbc.spi.R2dbcNonTransientResourceException",
        "io.r2dbc.spi.R2dbcTransientException",
        "java.net.ConnectException",
    )

    fun errorCodeOf(e: Throwable): ErrorCode = when {
        e is BusinessException -> e.errorCode
        isUnavailable(e) -> ErrorCode.UNAVAILABLE
        else -> ErrorCode.INTERNAL_ERROR
    }

    /** `RedisSystemException` 은 Lua 오류도 싸므로 그 자체로는 아니다 — 원인이 연결 계열일 때만 장애로 본다. */
    fun isUnavailable(e: Throwable): Boolean = causeChain(e).any { cause ->
        cause is DependencyUnavailableException ||
            generateSequence<Class<*>>(cause.javaClass) { it.superclass }.any { it.name in UNAVAILABLE_TYPES }
    }

    /** 자신부터 원인까지. 순환하는 사슬은 이미 본 예외에서 끊는다. */
    private fun causeChain(e: Throwable): Sequence<Throwable> {
        val seen = HashSet<Throwable>()
        return generateSequence(e) { it.cause }.takeWhile { seen.add(it) }
    }
}
