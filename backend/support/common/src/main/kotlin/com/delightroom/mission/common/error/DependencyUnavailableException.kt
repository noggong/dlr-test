package com.delightroom.mission.common.error

/**
 * 의존 서비스(Redis · PostgreSQL · 그룹 채널)를 지금 쓸 수 없다 — 응답 전이면 503 [ErrorCode.UNAVAILABLE] 이다(prd D21 · LLD platform §4.1).
 * 우리 코드가 의존 서비스 장애를 알릴 때 이것을 상속한다. 외부 라이브러리 예외는 [FailureClassifier] 가 이름으로 본다.
 */
open class DependencyUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
