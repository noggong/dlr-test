package com.delightroom.mission.core.port

import java.time.Duration
import java.time.LocalDate

/**
 * 로컬 데모 시계의 런타임 이동량(µs) 정본 — Redis 키 `demo:clock`(LLD platform §4.4). 세 앱이 같은 값을 읽는다.
 * 경계의 일 완료 표시 — Redis Hash `demo:jobs`(LLD §4.4-5 · SPEC P16).
 */
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

    /** `demo:jobs` 의 [field] 를 [date] 로 — 지금 값보다 클 때만(없으면 쓴다). 바꿨으면 true. */
    suspend fun markDone(field: String, date: LocalDate): Boolean

    /** `demo:jobs` 전부(필드 → 날짜). 키가 없으면 빈 맵. */
    suspend fun doneDates(): Map<String, LocalDate>

    /** 앞 이동이 멈춘 경계(`demo:pending` — `<일>@<날짜>`, SPEC P17). 없으면 null. */
    suspend fun pending(): String?

    /** [value] 로 바꾼다 — null 이면 지운다. */
    suspend fun setPending(value: String?)
}
