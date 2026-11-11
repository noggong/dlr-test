package com.delightroom.mission.core.port

import com.delightroom.mission.core.domain.progress.CheatFailRecord
import com.delightroom.mission.core.domain.progress.GroupDecidedRecord
import com.delightroom.mission.core.domain.progress.GroupRankingFinalRecord
import com.delightroom.mission.core.domain.progress.PassRecord
import com.delightroom.mission.core.domain.progress.SolveRecord

/**
 * solve-signal 이벤트와 deadline-result 의 `group.ranking_final` 을 PostgreSQL 에 남기는 포트(LLD solve-signal §2.3 · LLD platform §3) — `worker: persister` 가 부른다.
 * 메서드 하나가 한 트랜잭션이고, 돌아오면 커밋된 것이다. 같은 기록을 두 번 넘겨도 행이 같다(멱등).
 */
interface SignalEventRepository {
    suspend fun recordSolve(record: SolveRecord)

    suspend fun recordPass(record: PassRecord)

    suspend fun recordCheatFail(record: CheatFailRecord)

    /** `group_results` 를 이번에 넣었으면 true(이미 있었으면 false — 멤버 행은 그때만 넣는다). */
    suspend fun recordGroupDecided(record: GroupDecidedRecord): Boolean

    /**
     * `group.ranking_final` — `group_result_members` 를 확정 랭킹으로 덮는다(upsert · LLD platform §3). `group_results` 가 없으면
     * (앞의 `group.decided` 를 형식 오류로 건너뛴 경우) 같은 값으로 넣는다 — 이미 있으면 그대로(정확히 한 번).
     */
    suspend fun recordRankingFinal(record: GroupRankingFinalRecord)
}
