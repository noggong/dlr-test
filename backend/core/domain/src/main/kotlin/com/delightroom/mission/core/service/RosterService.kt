package com.delightroom.mission.core.service

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 그날 명단 찾기 · 만들기(LLD game-session §3.4). `GameService` 와 `worker: member` 가 같이 쓴다.
 * 그 날짜 07:00 전에는 부르지 않는다 — 07:00 전 명단은 확정이 아니다(§3.4-4).
 */
@Service
class RosterService(
    private val rosters: RosterRepository,
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val dailyProgress: DailyProgressStore,
    private val gameClock: GameClock,
) {
    suspend fun findOrCreate(gid: GroupId, date: LocalDate): DayRoster {
        rosters.find(gid, date)?.let { return it }
        check(gameClock.now() >= gameClock.startAt(date)) { "07:00 전에는 명단을 만들지 않는다: date=$date" }

        val members = users.findMembersJoinedBefore(listOf(gid), gameClock.startAt(date))[gid].orEmpty()
        val name = checkNotNull(groups.findName(gid)) { "그룹이 없다: ${gid.value}" }
        val built = DayRoster(gid, date, name, members)
        if (!rosters.createIfAbsent(built)) {
            // 다른 요청 · worker: member 가 먼저 만들었다 — 같은 규칙이라 내용도 같지만 정본(Redis)을 돌려준다
            return checkNotNull(rosters.find(gid, date)) { "명단이 이미 있다더니 없다: gid=${gid.value} date=$date" }
        }
        if (built.size >= MIN_PLAYING) insertNotStarted(built)
        return built
    }

    /** Q10 — 실패해도 요청은 계속한다. `worker: member` 가 모든 그룹에 다시 넣는다(§3.4-3 · §3.5-4). */
    private suspend fun insertNotStarted(roster: DayRoster) {
        try {
            dailyProgress.insertNotStarted(roster.date, roster.members.keys.map { it to roster.gid })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "daily_progress -1 행 실패 — worker: member 가 다시 넣는다: gid=${roster.gid.value} date=${roster.date}", e)
        }
    }

    private companion object {
        /** core 서비스는 프레임워크 로깅에 기대지 않는다(ArchitectureTest) — JDK System.Logger. */
        val log: System.Logger = System.getLogger(RosterService::class.java.name)

        /** 그룹 Hash · -1 행을 두는 최소 인원(1명 그룹은 결과가 없다 — HLD §5). */
        const val MIN_PLAYING = 2
    }
}
