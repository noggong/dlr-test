package com.delightroom.mission.infra.rdb

import com.delightroom.mission.core.domain.event.DeadLetter
import com.delightroom.mission.core.port.DeadLetterStore
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * persister dead-letter → `persister_dead_letters`(LLD platform §3.1 dead-letter 5). 한 문장이라 따로 트랜잭션을 열지 않는다.
 * 칸은 `jsonb_object(text[], text[])` 로 JSON 객체를 만든다. 같은 (bucket, stream_id) 는 실패 수 · 시각만 넓히고 사유는 늦은 실패의 것으로 둔다
 * (원본 칸은 같은 메시지라 그대로).
 */
internal class RdbDeadLetterStore(
    private val db: DatabaseClient,
) : DeadLetterStore {
    override suspend fun put(letter: DeadLetter) {
        val keys = letter.fields.keys.toList()
        // PostgreSQL text · jsonb 는 NUL 을 받지 못한다 — 이상한 메시지일수록 쌓여야 하니 글자로 바꾼다(LLD §3.1 dead-letter 5)
        db.sql(
            "INSERT INTO persister_dead_letters " +
                "(bucket, stream_id, event_type, fields, failure_count, last_error, first_failed_at, last_failed_at) " +
                "VALUES (:b, :id, :type, jsonb_object(CAST(:keys AS text[]), CAST(:values AS text[])), :n, :error, :first, :last) " +
                "ON CONFLICT (bucket, stream_id) DO UPDATE SET " +
                "failure_count = GREATEST(persister_dead_letters.failure_count, EXCLUDED.failure_count), " +
                "last_error = CASE WHEN EXCLUDED.last_failed_at >= persister_dead_letters.last_failed_at " +
                "THEN EXCLUDED.last_error ELSE persister_dead_letters.last_error END, " +
                "first_failed_at = LEAST(persister_dead_letters.first_failed_at, EXCLUDED.first_failed_at), " +
                "last_failed_at = GREATEST(persister_dead_letters.last_failed_at, EXCLUDED.last_failed_at)",
        )
            .bind("b", letter.bucket)
            .bind("id", letter.streamId)
            .let { spec -> letter.type?.let { spec.bind("type", noNul(it)) } ?: spec.bindNull("type", String::class.java) }
            .bind("keys", keys.map(::noNul).toTypedArray())
            .bind("values", keys.map { noNul(letter.fields.getValue(it)) }.toTypedArray())
            .bind("n", letter.failureCount)
            .bind("error", noNul(letter.lastError).take(MAX_ERROR))
            .bind("first", odt(letter.firstFailedAt))
            .bind("last", odt(letter.lastFailedAt))
            .fetch().awaitRowsUpdated()
    }

    private fun noNul(s: String): String = s.replace("\u0000", "\\u0000")

    private fun odt(t: Instant): OffsetDateTime = t.atOffset(ZoneOffset.UTC)

    private companion object {
        /** `last_error` 는 앞 2,000자(COMMENT). */
        const val MAX_ERROR = 2_000
    }
}
