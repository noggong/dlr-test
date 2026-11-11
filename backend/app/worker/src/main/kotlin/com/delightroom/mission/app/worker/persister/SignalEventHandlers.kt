package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.domain.progress.CheatFailRecord
import com.delightroom.mission.core.domain.progress.GroupDecidedRecord
import com.delightroom.mission.core.domain.progress.PassRecord
import com.delightroom.mission.core.domain.progress.SignalRecords
import com.delightroom.mission.core.domain.progress.SolveRecord
import com.delightroom.mission.core.port.SignalEventRepository
import org.springframework.stereotype.Component

/**
 * solve-signal 의 persister 처리기(LLD solve-signal §3.6) — `solve.applied` · `pass.used` · `member.cheat_failed` · `group.decided`.
 *
 * 칸을 [SignalRecords] 로 읽고(틀리면 [MalformedEventException]) 저장소 메서드 하나(= 한 트랜잭션)를 부른다 — 돌아오면 커밋된 것이다.
 * `group.decided` 의 결과 캐시 갱신(커밋 뒤)은 T-DR-6 이 여기에 더한다.
 */
@Component
class SignalEventHandlers(
    private val repository: SignalEventRepository,
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
            is GroupDecidedRecord -> repository.recordGroupDecided(record)
        }
    }
}
