package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameDay
import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock

/** 게임 정보 조회 결과(FR-3). */
class GameInfo(
    val day: GameDay,
    val group: Group,
    val roster: List<User>,
)

/** 미션 시작 결과 — 문제은행과 시작한 진행(R-B1). */
class MissionStart(
    val progress: MissionProgress,
    val bank: QuestionBank,
)

/** game-session — 게임 정보 · 그날 명단 · 미션 시작 · 문제은행(FR-1 · FR-3 · §5.2). */
@Service
class GameService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val questions: QuestionRepository,
    private val progresses: ProgressRepository,
    private val properties: GameProperties,
    private val clock: Clock,
) {
    suspend fun gameInfo(userId: UserId): GameInfo =
        // TODO(game-session)
        throw NotImplementedYet("game-session")

    suspend fun startMission(userId: UserId): MissionStart =
        // TODO(game-session): 07:00 이후 첫 수령 = 미션 시작 · 1명 그룹 거부(R-G6)
        throw NotImplementedYet("game-session")
}
