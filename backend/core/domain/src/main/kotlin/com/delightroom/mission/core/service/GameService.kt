package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.UserRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** 게임 정보 조회 결과(FR-3 · SPEC §2.1). [members] 는 로그인 아이디 오름차순. */
class GameInfo(
    val date: LocalDate,
    val startAt: Instant,
    val deadlineAt: Instant,
    val serverTime: Instant,
    val groupName: GroupName,
    val members: List<LoginId>,
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
    private val gameClock: GameClock,
    private val rosters: RosterService,
) {
    /**
     * 게임 정보(LLD §3.1). 날짜는 08:00 부터 다음 날(GS1). 07:00 부터는 확정 명단(없으면 만든다 — §3.4),
     * 07:00 전에는 명단을 만들지 않고 PostgreSQL 에서 지금까지의 가입자를 읽는다(Q4 · Q6).
     */
    suspend fun gameInfo(groupId: GroupId): GameInfo {
        val now = gameClock.now()
        val date = gameClock.infoDate(now)
        val startAt = gameClock.startAt(date)
        val (name, members) = if (now >= startAt) {
            val roster = rosters.findOrCreate(groupId, date)
            roster.name to roster.sortedLoginIds()
        } else {
            val joined = users.findMembersJoinedBefore(listOf(groupId), startAt)[groupId].orEmpty()
            val name = checkNotNull(groups.findName(groupId)) { "그룹이 없다: ${groupId.value}" }
            name to joined.values.sortedBy { it.value }
        }
        return GameInfo(date, startAt, gameClock.deadlineAt(date), now, name, members)
    }

    suspend fun startMission(userId: UserId): MissionStart =
        // TODO(game-session): 07:00 이후 첫 수령 = 미션 시작 · 1명 그룹 거부(R-G6)
        throw NotImplementedYet("game-session")
}
