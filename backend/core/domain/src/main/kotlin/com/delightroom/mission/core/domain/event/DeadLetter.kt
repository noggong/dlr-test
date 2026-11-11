package com.delightroom.mission.core.domain.event

import java.time.Instant

/**
 * `worker: persister` 가 처리하지 못한 Stream 메시지 한 건(prd D20 · LLD platform §3.1 dead-letter) — `persister_dead_letters` 한 행.
 * (bucket, streamId) 가 메시지 하나다.
 */
data class DeadLetter(
    val bucket: Int,
    /** Stream id(`<ms>-<seq>`). */
    val streamId: String,
    /** 칸 `type`. 없던 메시지면 null. */
    val type: String?,
    /** Stream 칸 전체(`type` 포함). */
    val fields: Map<String, String>,
    /** 쌓을 때까지 센 실패 수 — 일시 장애는 세지 않는다. */
    val failureCount: Int,
    /** 마지막 실패 사유. */
    val lastError: String,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
)
