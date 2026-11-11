package com.delightroom.mission.app.worker.persister

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 처리기 실패가 dead-letter 횟수에 드는지(LLD platform §3.1 dead-letter 1) — DB · Redis · 네트워크 상태로 나는 일시 장애는 세지 않는다.
 *
 * 예외와 원인 사슬의 클래스 계층에 [TRANSIENT] 이름이 하나라도 있으면 일시 장애다. `app:worker` 는 저장소 라이브러리를
 * 컴파일 의존으로 갖지 않아(포트만 쓴다) 클래스 이름으로 본다.
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
    )

    /** true 면 센다(메시지 탓일 수 있는 실패). */
    fun counts(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var t: Throwable? = error
        while (t != null && seen.add(t)) {
            if (isTransient(t.javaClass)) return false
            t = t.cause
        }
        return true
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
