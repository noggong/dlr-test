package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.event.DeadLetter

/**
 * persister dead-letter 저장소(prd D20 · LLD platform §3.1 dead-letter) — `worker: persister` 가 처리하지 못한 메시지를 PostgreSQL 에 쌓는다.
 * 다시 넣는 처리 프로세스는 범위 밖이라 쓰기만 있다.
 */
interface DeadLetterStore {
    /**
     * 한 문장(= 한 트랜잭션) — 돌아오면 커밋된 것이다. 같은 (bucket, streamId) 는 한 행이다(멱등 — 실패 수 · 시각만 넓힌다).
     * DB 가 안 되면 예외 — 부르는 쪽은 ACK 하지 않는다.
     */
    suspend fun put(letter: DeadLetter)
}
