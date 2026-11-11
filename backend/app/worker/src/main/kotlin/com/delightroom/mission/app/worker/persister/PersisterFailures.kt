package com.delightroom.mission.app.worker.persister

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 처리기 실패가 dead-letter 횟수에 드는지(LLD platform §3.1 dead-letter 1) — DB · Redis · 네트워크 상태로 나는 일시 장애는 세지 않는다.
 *
 * 예외와 원인 사슬의 클래스 계층에 [TRANSIENT] 이름이 하나라도 있거나, R2DBC 예외의 SQLSTATE 가 [TRANSIENT_SQLSTATE] 이면 일시 장애다.
 * `app:worker` 는 저장소 라이브러리를 컴파일 의존으로 갖지 않아(포트만 쓴다) 클래스 이름 · 리플렉션으로 본다.
 */
internal object PersisterFailures {
    private val TRANSIENT = setOf(
        // 커넥션 획득 실패 · R2DBC 비일시 자원 오류 · RedisConnectionFailureException
        "org.springframework.dao.DataAccessResourceFailureException",
        // 쿼리 타임아웃 · 일시 자원 오류 · 잠금 · 직렬화 실패
        "org.springframework.dao.TransientDataAccessException",
        "org.springframework.dao.RecoverableDataAccessException",
        "io.r2dbc.spi.R2dbcTransientException",
        "io.r2dbc.spi.R2dbcNonTransientResourceException",
        "java.sql.SQLTransientException",
        "java.sql.SQLRecoverableException",
        "java.io.IOException",
        "java.util.concurrent.TimeoutException",
        "kotlinx.coroutines.TimeoutCancellationException",
        RetryableEventException::class.java.name,
    )

    /**
     * r2dbc-postgresql 은 `08` 만 자원 예외로 바꾸고 나머지는 일반 예외(Spring 은 `UncategorizedR2dbcException`)라 SQLSTATE 로 본다 —
     * `08` 연결 · `40` 직렬화 · 교착 · `53` 자원(커넥션 고갈) · `57` 운영자 개입(종료 · 기동 중) · `58` 시스템 · `25006` 장애 조치 뒤 읽기 전용.
     */
    private val TRANSIENT_SQLSTATE_CLASSES = setOf("08", "40", "53", "57", "58")
    private val TRANSIENT_SQLSTATE = setOf("25006")
    private const val R2DBC_EXCEPTION = "io.r2dbc.spi.R2dbcException"

    /** true 면 센다(메시지 탓일 수 있는 실패). */
    fun counts(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var t: Throwable? = error
        while (t != null && seen.add(t)) {
            if (isTransient(t.javaClass) || isTransientSqlState(t)) return false
            t = t.cause
        }
        return true
    }

    private fun isTransientSqlState(t: Throwable): Boolean {
        if (!isA(t.javaClass, R2DBC_EXCEPTION)) return false
        val state = runCatching { t.javaClass.getMethod("getSqlState").invoke(t) as String? }.getOrNull() ?: return false
        return state in TRANSIENT_SQLSTATE || (state.length == 5 && state.take(2) in TRANSIENT_SQLSTATE_CLASSES)
    }

    private fun isA(type: Class<*>, name: String): Boolean {
        var c: Class<*>? = type
        while (c != null) {
            if (c.name == name) return true
            c = c.superclass
        }
        return false
    }

    private fun isTransient(type: Class<*>): Boolean {
        var c: Class<*>? = type
        while (c != null) {
            if (c.name in TRANSIENT) return true
            c = c.superclass
        }
        return false
    }
}
