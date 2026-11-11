package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.LatestResult
import java.time.LocalDate

/** 확정된 그룹 결과 보관. 한 (그룹, 날짜)에 한 번만 기록된다(NFR-2). */
interface GameResultRepository {
    /**
     * 그룹의 가장 최근 결과와 그날 랭킹 스냅샷(LLD deadline-result §2.3 쿼리 1 · 2). 결과 행이 없으면 null.
     * `rankingFinal` 은 PostgreSQL 에 칸이 없어 `false` 로 둔다 — 시각으로 정하는 것은 호출한 쪽(`BoardService`)이다.
     */
    suspend fun findLatest(gid: GroupId): LatestResult?

    suspend fun find(groupId: GroupId, date: LocalDate): GroupResult?

    /** 이미 기록돼 있으면 false. */
    suspend fun recordOnce(result: GroupResult): Boolean
}
