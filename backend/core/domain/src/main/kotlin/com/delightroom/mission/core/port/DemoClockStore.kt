package com.delightroom.mission.core.port

/** 로컬 데모 시계의 런타임 이동량(µs) 정본 — Redis 키 `demo:clock`(LLD platform §4.4). 세 앱이 같은 값을 읽는다. */
interface DemoClockStore {
    /** 지금 값. 키가 없으면 null. */
    suspend fun get(): Long?

    /** 키가 없을 때만 쓴다(`SET NX`). 썼으면 true. */
    suspend fun setIfAbsent(micros: Long): Boolean

    /** 지금 값이 [expected] 일 때만 [next] 로 바꾼다(한 번에 — Lua). 바꿨으면 true. */
    suspend fun compareAndSet(expected: Long, next: Long): Boolean
}
