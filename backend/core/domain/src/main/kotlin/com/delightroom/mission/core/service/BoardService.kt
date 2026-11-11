package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.Board
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.board.BoardView
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.GroupBoardRepository
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
    private val boards: GroupBoardRepository,
    private val properties: GameProperties,
) {
    /**
     * 오늘 내 그룹의 보는 사람 기준 현황판(LLD realtime-board §4.1 · SPEC §3). 판정 · 시각은 Lua `board_read` 가 정하고,
     * 앱 시계는 날짜만 고른다. 거부는 그 코드의 [BusinessException]. 그룹 Hash 가 틀리면 파서의 예외가 그대로 나간다(500).
     */
    suspend fun board(userId: UserId, groupId: GroupId): Board {
        val date = properties.now(clock).atZone(properties.zone).toLocalDate()
        return when (val read = boards.read(groupId, date, userId, BoardReadMode.BOARD)) {
            is BoardRead.Rejected -> throw BusinessException(read.code)
            is BoardRead.Opened ->
                BoardView.build(GroupDaySnapshot.parseFlat(date, read.fields), userId, read.now, properties.deadlineAt(date))
        }
    }

    suspend fun latestResult(userId: UserId): ResultView =
        // TODO(deadline-result): 가장 최근에 확정된 결과(prd D3 · openapi getLatestResult)
        throw NotImplementedYet("deadline-result")
}
