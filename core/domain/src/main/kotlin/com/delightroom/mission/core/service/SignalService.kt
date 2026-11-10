package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.progress.PassSignal
import com.delightroom.mission.core.domain.progress.SolveOutcome
import com.delightroom.mission.core.domain.progress.SolveSignal
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.ProgressEventPublisher
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionRepository
import org.springframework.stereotype.Service
import java.time.Clock

/** solve-signal — 풀이 · 패스 · 부정 실패 신호와 그룹 판정(FR-4 · §5.4 ~ §5.7 · NFR-2 · NFR-3). */
@Service
class SignalService(
    private val progresses: ProgressRepository,
    private val questions: QuestionRepository,
    private val results: GameResultRepository,
    private val publisher: ProgressEventPublisher,
    private val properties: GameProperties,
    private val clock: Clock,
) {
    suspend fun solve(userId: UserId, signal: SolveSignal): SolveOutcome =
        // TODO(solve-signal): 멱등 반영 · 마감 가드(D1) · 그룹 판정 · 이벤트 발행
        throw NotImplementedYet("solve-signal")

    suspend fun pass(userId: UserId, signal: PassSignal): MissionProgress =
        // TODO(solve-signal): 하루 1번(R-P5) · 출제 기록(R-P3)
        throw NotImplementedYet("solve-signal")

    suspend fun failByCheat(userId: UserId): MissionProgress =
        // TODO(solve-signal): 부정 실패(R-F2) · 그룹 판정(R-F6)
        throw NotImplementedYet("solve-signal")
}
