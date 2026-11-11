package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.domain.event.StreamEvent

/**
 * `worker: persister` 처리기 — 기능 일감이 이 인터페이스의 빈을 더하면 [StreamPersister] 가 `type` 별로 부른다(LLD platform §3.1).
 *
 * - [handle] 이 **돌아오면 커밋된 것**이다. 트랜잭션은 처리기 안에서 열고(저장소 포트), 커밋 뒤 일(결과 캐시)도 처리기 안에서 한다.
 * - 예외 = 커밋 안 됨 → ACK 하지 않고 같은 메시지를 다시 준다. 그래서 같은 메시지가 두 번 이상 올 수 있다 — SQL 은 멱등이어야 한다.
 * - 다시 줘도 고칠 수 없는 메시지는 [MalformedEventException] 을 던진다 — 로그를 남기고 dead-letter 에 쌓은 뒤 ACK 한다(prd D20).
 * - 메시지 탓이 아닌데(예: 커밋 뒤 결과 캐시 장애) 다시 받기를 바라면 [RetryableEventException] — dead-letter 횟수에 들지 않는다.
 */
interface PersisterEventHandler {
    /** 맡는 `type`(HLD platform §4.2 표). 한 `type` 은 처리기 하나만 맡는다 — 겹치면 기동 실패. */
    val types: Set<String>

    suspend fun handle(event: StreamEvent)
}

/** 칸 형식이 틀려 다시 처리해도 성공할 수 없는 메시지 — [StreamPersister] 가 ERROR 로그 뒤 처음 실패에 dead-letter 로 쌓고 ACK 한다. */
class MalformedEventException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 메시지는 멀쩡한데 처리기가 다시 받기를 바라는 실패(LLD platform §3.1 dead-letter 1) — [StreamPersister] 는 ACK 하지 않고 다시 주되
 * dead-letter 횟수에 세지 않는다(장애가 길어도 정상 이벤트가 dead-letter 로 가지 않게).
 */
class RetryableEventException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
