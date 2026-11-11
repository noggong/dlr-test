package com.delightroom.mission.core.port

import java.time.Duration

/** 로컬 데모 시계의 런타임 이동량(µs) 정본 — Redis 키 `demo:clock`(LLD platform §4.4). 세 앱이 같은 값을 읽는다. */
interface DemoClockStore {
    /** 지금 값. 키가 없으면 null. */
    suspend fun get(): Long?

    /** 키가 없을 때만 쓴다(`SET NX`). 썼으면 true. */
    suspend fun setIfAbsent(micros: Long): Boolean

    /** 지금 값이 [expected] 일 때만 [next] 로 바꾼다(한 번에 — Lua). 바꿨으면 true. */
    suspend fun compareAndSet(expected: Long, next: Long): Boolean

    /**
     * 로그인 세션 키(`sess:{<uid>}`)의 남은 TTL 을 [by] 만큼 줄인다(남은 것이 없으면 지운다). 시각을 앞으로 옮긴 뒤 부른다 —
     * refresh token 만료는 게임 시각으로 재고 세션 키 TTL 은 실제 시간으로 흘러, 줄이지 않으면 하루를 옮긴 뒤 refresh 는 만료인데
     * 세션 키가 남아 다시 로그인이 `409 ALREADY_LOGGED_IN` 이 된다(LLD platform §4.4-3). 줄인 키 수를 돌려준다.
     */
    suspend fun shortenSessions(by: Duration): Int
}
