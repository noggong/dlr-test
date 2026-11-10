package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.domain.board.Board
import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** 결과 조회 — 결과와 다음 게임 시작 시각을 함께(R-V2). */
class ResultView(
    val result: GroupResult?,
    val nextGameStartAt: Instant,
)

/** realtime-board · deadline-result — 현황판과 결과 조회(FR-5 · FR-7 · §5.8 · §5.9). */
@Service
class BoardService(
    private val users: UserRepository,
    private val progresses: ProgressRepository,
    private val results: GameResultRepository,
    private val clock: Clock,
) {
    suspend fun board(userId: UserId): Board =
        // TODO(realtime-board): 현재 진행도 반영 현황판(R-R4)
        throw NotImplementedYet("realtime-board")

    suspend fun latestResult(userId: UserId): ResultView =
        // TODO(deadline-result): 가장 최근에 확정된 결과(prd D3 · openapi getLatestResult)
        throw NotImplementedYet("deadline-result")
}
