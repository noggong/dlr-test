package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.Board
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GameResultRepository
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.ResultCache
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** 결과 조회 — 결과 · 내 순위 · 다음 게임 시작 시각(deadline-result SPEC §2). */
class ResultView(
    val result: LatestResult,
    /** [LatestResult.ranking] 의 내 줄 rank, 없으면 null(DR9). */
    val myRank: Int?,
    val nextGameStartAt: Instant,
)

/** realtime-board · deadline-result — 현황판과 결과 조회(FR-5 · FR-7 · §5.8 · §5.9). */
@Service
class BoardService(
    private val users: UserRepository,
    private val progresses: ProgressRepository,
    private val results: GameResultRepository,
    private val groupDays: GroupDayRepository,
    private val resultCache: ResultCache,
    private val properties: GameProperties,
    private val clock: Clock,
) {
    suspend fun board(userId: UserId): Board =
        // TODO(realtime-board): 현재 진행도 반영 현황판(R-R4)
        throw NotImplementedYet("realtime-board")

    /**
     * 가장 최근 결과(LLD deadline-result §3.3 · SPEC DR1). 상태를 바꾸지 않는다(DR11) — 쓰는 것은 결과 캐시 채우기뿐이다.
     * 결과가 한 번도 없으면 `NO_RESULT_YET`.
     */
    suspend fun latestResult(loginId: LoginId, groupId: GroupId): ResultView {
        val now = properties.now(clock)
        val today = now.atZone(properties.zone).toLocalDate()
        val result = todayResult(groupId, today, now)
            ?: resultCache.get(groupId)
            ?: fromDatabase(groupId, now)
            ?: throw BusinessException(ErrorCode.NO_RESULT_YET)
        val todayStart = properties.startAt(today)
        val nextGameStartAt = if (now.isBefore(todayStart)) todayStart else properties.startAt(today.plusDays(1))
        return ResultView(result, result.rankOf(loginId), nextGameStartAt)
    }

    /** §3.3 1 · 2 — 오늘 Hash 를 `HMGET` 한 번으로. 결과가 있으면 그 결과, 08:00 뒤인데 없으면 PENDING, 그 밖은 null. */
    private suspend fun todayResult(groupId: GroupId, today: LocalDate, now: Instant): LatestResult? {
        val state = groupDays.findResultState(groupId, today) ?: return null
        state.toLatest(today)?.let { return it }
        return if (now.isBefore(properties.deadlineAt(today))) null else LatestResult.pending(today)
    }

    /**
     * §3.3 4 — PostgreSQL 의 가장 최근 결과. `rankingFinal` 은 그날 08:00:03 이 지났는지로 정하고(§2.3), 확정 랭킹일 때만 캐시를 채운다 —
     * 잠정 값을 캐시하면 25시간 동안 `rankingFinal false` 로 남는다(DR5).
     */
    private suspend fun fromDatabase(groupId: GroupId, now: Instant): LatestResult? {
        val stored = results.findLatest(groupId) ?: return null
        val result = stored.copy(rankingFinal = !now.isBefore(properties.graceEndAt(stored.date)))
        if (result.rankingFinal) resultCache.putIfAbsent(groupId, result)
        return result
    }
}
