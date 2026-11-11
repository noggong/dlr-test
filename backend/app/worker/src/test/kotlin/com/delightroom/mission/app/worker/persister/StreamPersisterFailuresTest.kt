package com.delightroom.mission.app.worker.persister

import io.r2dbc.spi.R2dbcNonTransientException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.UncategorizedR2dbcException

/** 실패 분류(ORACLE platform O21 단위 · LLD platform §3.1 dead-letter 1) — 일시 장애는 dead-letter 횟수에 세지 않는다. */
class StreamPersisterFailuresTest {
    /** r2dbc-postgresql 의 일반 서버 오류 꼴(추상 클래스의 익명 하위). */
    private fun pgError(reason: String, sqlState: String?): R2dbcNonTransientException =
        object : R2dbcNonTransientException(reason, sqlState) {}

    /** Spring 이 r2dbc-postgresql 의 일반 예외를 감싸는 꼴. */
    private fun uncategorized(sqlState: String) =
        UncategorizedR2dbcException("executeMany", "SELECT 1", pgError("주입 $sqlState", sqlState))

    @Test
    fun `O21 연결 · 자원 · 장애 조치 계열은 세지 않는다`() {
        val transient = listOf(
            DataAccessResourceFailureException("커넥션 끊김"),
            IllegalStateException("감쌈", DataAccessResourceFailureException("커넥션 끊김")),
            R2dbcNonTransientResourceException("연결 거부", "08001"),
            uncategorized("53300"), // too_many_connections
            uncategorized("57P01"), // admin_shutdown
            uncategorized("57P03"), // cannot_connect_now
            uncategorized("25006"), // read_only_sql_transaction — 장애 조치 뒤 옛 primary
            uncategorized("40001"), // serialization_failure
            RetryableEventException("결과 캐시 장애"),
            java.io.IOException("소켓"),
        )
        transient.forEach { assertThat(PersisterFailures.counts(it)).describedAs(it.toString()).isFalse() }
    }

    @Test
    fun `O21 처리기 자체 타임아웃은 세지 않는다`(): Unit = runBlocking {
        val timeout = runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()
        assertThat(timeout).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(PersisterFailures.counts(timeout!!)).isFalse()
    }

    @Test
    fun `O21 메시지 탓일 수 있는 실패는 센다`() {
        val counted = listOf(
            IllegalStateException("처리기 버그"),
            NotImplementedError("TODO()"),
            DataIntegrityViolationException("FK 위반"),
            uncategorized("23505"), // unique_violation
            uncategorized("22001"), // string_data_right_truncation
            pgError("상태 없음", null),
        )
        counted.forEach { assertThat(PersisterFailures.counts(it)).describedAs(it.toString()).isTrue() }
    }
}
