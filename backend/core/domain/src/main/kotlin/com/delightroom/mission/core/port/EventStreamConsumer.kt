package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.event.ClaimedEvents
import com.delightroom.mission.core.domain.event.StreamEvent
import java.time.Duration

/**
 * 버킷 이벤트 Stream `ev:{<b>}` 을 consumer group `persister` 로 소비하는 포트(LLD platform §3.1).
 * 읽기는 막지 않는다(`BLOCK` 없음) — 없으면 빈 목록이다. 반환 목록은 Stream id 순서다.
 */
interface EventStreamConsumer {
    /** 버킷 [bucket] 의 Stream 과 consumer group 이 없으면 만든다(그룹 시작 id `0` — 그 전 메시지도 읽는다). */
    suspend fun ensureGroup(bucket: Int)

    /** 아직 아무에게도 배달되지 않은 메시지를 [count] 개까지(`XREADGROUP … >`). */
    suspend fun readNew(bucket: Int, consumer: String, count: Int): List<StreamEvent>

    /** [consumer] 에게 배달됐지만 ACK 하지 않은 메시지를 [count] 개까지(`XREADGROUP … 0`). */
    suspend fun readOwnPending(bucket: Int, consumer: String, count: Int): List<StreamEvent>

    /** [minIdle] 넘게 ACK 되지 않은 메시지를 [consumer] 로 옮겨 가져온다(`XAUTOCLAIM` 한 번 — [cursor] 부터). */
    suspend fun claimIdle(bucket: Int, consumer: String, minIdle: Duration, count: Int, cursor: String): ClaimedEvents

    /** [ids] 를 ACK 하고, 그룹의 pending 을 지우지 않는 범위에서 Stream 앞부분을 잘라 낸다. ACK 한 수를 돌려준다. */
    suspend fun ack(bucket: Int, ids: List<String>): Long
}
