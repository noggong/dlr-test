package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.result.ResultCachePut

/**
 * 결과 캐시 `result:{<gid>}`(LLD deadline-result §2.4) — 그룹의 가장 최근 확정 결과. 정본은 PostgreSQL 이다.
 * 조회는 [get] · [putIfAbsent], persister 는 커밋 뒤 [putIfNotOlder] 를 쓴다.
 */
interface ResultCache {
    /** 캐시 값. 없거나 형식을 읽지 못하면 null — 읽지 못한 값은 지운다(LLD §5). */
    suspend fun get(gid: GroupId): LatestResult?

    /** 조회의 cache-aside 채우기 — `SET NX EX`(이미 있으면 그대로). 쓰기 실패는 로그만 남긴다(캐시는 정본이 아니다). */
    suspend fun putIfAbsent(gid: GroupId, result: LatestResult)

    /**
     * persister 의 커밋 뒤 갱신(LLD deadline-result §3.4 · Lua `result_cache_put` §4.3) — 캐시 날짜가 더 늦거나, 같은 날 확정 랭킹을
     * 잠정 값으로 덮게 되면 쓰지 않는다. 썼으면 [ResultCachePut.WRITTEN], 더 새 값이 있으면 [ResultCachePut.KEPT].
     * Redis 오류면 로그를 남기고, 확정 랭킹([LatestResult.rankingFinal])이면 키를 지워 본다 — 지웠으면 [ResultCachePut.EVICTED]
     * (같은 날 잠정 값이 25시간 남지 않게 · 다음 조회가 PostgreSQL 로 채운다), 못 지웠거나 잠정이면 [ResultCachePut.FAILED]. 던지지 않는다.
     */
    suspend fun putIfNotOlder(gid: GroupId, result: LatestResult): ResultCachePut
}
