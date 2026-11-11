package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.domain.progress.CheatFailRecord
import com.delightroom.mission.core.domain.progress.GroupDecidedRecord
import com.delightroom.mission.core.domain.progress.GroupRankingFinalRecord
import com.delightroom.mission.core.domain.progress.GroupResultRecord
import com.delightroom.mission.core.domain.progress.PassRecord
import com.delightroom.mission.core.domain.progress.SignalRecords
import com.delightroom.mission.core.domain.progress.SolveRecord
import com.delightroom.mission.core.domain.result.ResultCachePut
import com.delightroom.mission.core.port.ResultCache
import com.delightroom.mission.core.port.SignalEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * solve-signal · deadline-result 의 persister 처리기(LLD solve-signal §3.6 · LLD deadline-result §3.4) —
 * `solve.applied` · `pass.used` · `member.cheat_failed` · `group.decided` · `group.ranking_final`.
 *
 * 칸을 [SignalRecords] 로 읽고(틀리면 [MalformedEventException]) 저장소 메서드 하나(= 한 트랜잭션)를 부른다 — 돌아오면 커밋된 것이다.
 * 그룹 결과 두 이벤트는 **커밋 뒤에** 결과 캐시를 날짜 비교로 갱신한다(`ResultCache.putIfNotOlder`). 커밋이 실패하면(예외) 캐시에 닿지 않고,
 * 캐시 쓰기가 실패해도 로그만 남기고 ACK 한다 — 캐시는 정본이 아니다(다음 조회가 PostgreSQL 로 채운다). 단 확정 랭킹을 쓰지도,
 * 남은 잠정 값을 지우지도 못했으면(`FAILED`) 예외로 다시 받는다 — 같은 날 잠정 랭킹이 25시간 남아 NFR-4 를 어기지 않게(SQL 은 멱등).
 */
@Component
class SignalEventHandlers(
    private val repository: SignalEventRepository,
    private val resultCache: ResultCache,
) : PersisterEventHandler {
    override val types: Set<String> = SignalRecords.TYPES

    override suspend fun handle(event: StreamEvent) {
        val record = try {
            SignalRecords.parse(event.fields)
        } catch (e: IllegalArgumentException) {
            throw MalformedEventException("${event.type} ${event.id}: ${e.message}", e)
        }
        when (record) {
            is SolveRecord -> repository.recordSolve(record)
            is PassRecord -> repository.recordPass(record)
            is CheatFailRecord -> repository.recordCheatFail(record)
            is GroupDecidedRecord -> {
                repository.recordGroupDecided(record)
                refreshCache(record)
            }
            is GroupRankingFinalRecord -> {
                repository.recordRankingFinal(record)
                refreshCache(record)
            }
        }
    }

    /** 반환 `KEPT`(더 새 값이 있음 — 옛 결과의 재처리)는 정상이고, `FAILED` 는 구현이 이미 로그를 남겼다. */
    private suspend fun refreshCache(record: GroupResultRecord) {
        val latest = record.toLatest()
        if (latest == null) {
            log.warn("result_cache.skipped gid={} date={} reason=ranking_unreadable", record.groupId.value, record.date)
            return
        }
        val outcome = resultCache.putIfNotOlder(record.groupId, latest)
        // 캐시(Redis) 장애라 메시지 탓이 아니다 — dead-letter 횟수에 세지 않고 다시 받는다(prd D20)
        if (latest.rankingFinal && outcome == ResultCachePut.FAILED) {
            throw RetryableEventException(
                "확정 랭킹을 결과 캐시에 쓰지도 지우지도 못함 gid=${record.groupId.value} date=${record.date} — 다시 처리한다",
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SignalEventHandlers::class.java)
    }
}
