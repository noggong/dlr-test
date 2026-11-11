package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.LatestResult

/**
 * 결과 캐시 `result:{<gid>}`(LLD deadline-result §2.4) — 그룹의 가장 최근 확정 결과. 정본은 PostgreSQL 이다.
 * persister 쪽 날짜 비교 쓰기(`putIfNotOlder`)는 T-DR-6 이 더한다.
 */
interface ResultCache {
    /** 캐시 값. 없거나 형식을 읽지 못하면 null — 읽지 못한 값은 지운다(LLD §5). */
    suspend fun get(gid: GroupId): LatestResult?

    /** 조회의 cache-aside 채우기 — `SET NX EX`(이미 있으면 그대로). 쓰기 실패는 로그만 남긴다(캐시는 정본이 아니다). */
    suspend fun putIfAbsent(gid: GroupId, result: LatestResult)
}
