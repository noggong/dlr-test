package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.question.BankDraw
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.Question
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.question.QuestionBankBuilder
import com.delightroom.mission.core.domain.question.QuestionCatalog
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionBankCache
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

/**
 * 미션 시작 결과(SPEC §2.2) — 그날 처음 시작한 시각과 문제은행. [questions] 는 난이도 1 → 2 → 3, 그 안은 은행 순서(GS6 · GS7).
 */
class MissionStart(
    val startedAt: Instant,
    val questions: List<Question>,
)

/** 진입 신호 결과(SPEC §2.3) — 그 번호의 지금 문제(= 요청 값) · 진행도(= `questionNo − 1`) · 내 상태(언제나 `IN_PROGRESS`). */
class ShownQuestion(
    val questionNo: Int,
    val questionId: QuestionId,
    val progress: Int,
    val missionStatus: MemberStatus,
)

/** game-session — 게임 정보 · 그날 명단 · 미션 시작 · 문제은행 · 진입 신호(FR-1 · FR-3 · §5.2). */
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
    private val bankCache: QuestionBankCache,
    /** 문제 캐시 — api 만 적재한다. realtime · worker 도 이 서비스를 스캔하지만 빈이 없어 null 이고 문제은행을 부르지 않는다(LLD v6.1 §1). */
    private val catalog: QuestionCatalog?,
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

    /**
     * 문제은행 = 미션 시작(LLD §3.2 · SPEC GS14). 시작(Lua `start`)이 은행보다 먼저다 — 시작 시각에 은행 계산이 끼지 않아
     * 지각 판정이 공정하고, 그 대가로 `QUESTIONS_EXHAUSTED` 유저도 「시작」으로 남는다(GS5).
     */
    suspend fun startMission(userId: UserId, groupId: GroupId): MissionStart {
        val date = gameClock.playDate(gameClock.now())
        val started = when (val outcome = start(userId, groupId, date)) {
            is MissionStartOutcome.Started -> outcome
            is MissionStartOutcome.Rejected -> throw BusinessException(outcome.code)
            MissionStartOutcome.NoRoster -> error("명단을 만든 뒤에도 NO_ROSTER: gid=${groupId.value} date=$date")
        }
        val catalog = checkNotNull(catalog) { "문제 캐시가 없다 — 문제은행은 api 앱만 준다" }
        val bank = bankFor(userId, date, catalog)
        // 캐시에 없는 ID(문제 삭제 — 운영상 없음)는 건너뛴다(LLD §5)
        val questions = Difficulty.entries.flatMap { d -> bank.forDifficulty(d).mapNotNull { catalog.byId[it] } }
        return MissionStart(started.startedAt, questions)
    }

    /**
     * 진입 신호(LLD §3.3 · SPEC GS9 ~ GS13) — 본문 검증(`questionNo` 1~3 · PK 형식)은 컨트롤러가 먼저 끝냈다.
     * 문제 캐시로 「있는 PK 이고 난이도 = 번호」만 보고 거부는 Lua 에 넘긴다 — 시간 · 그룹원 · 상태 판정이 먼저다(GS9).
     */
    suspend fun markShown(userId: UserId, groupId: GroupId, questionNo: Int, questionId: QuestionId): ShownQuestion {
        val catalog = checkNotNull(catalog) { "문제 캐시가 없다 — 진입 신호는 api 앱만 받는다" }
        val date = gameClock.playDate(gameClock.now())
        val question = catalog.byPk[questionId]?.takeIf { it.difficulty.level == questionNo }
        val outcome = withRoster(groupId, date, ShownOutcome.NoRoster) {
            progresses.markShown(userId, groupId, date, questionNo, questionId, question?.internalId)
        }
        return when (outcome) {
            is ShownOutcome.Shown -> ShownQuestion(questionNo, questionId, outcome.progress, outcome.status)
            is ShownOutcome.Rejected -> throw BusinessException(outcome.code)
            ShownOutcome.NoRoster -> error("명단을 만든 뒤에도 NO_ROSTER: gid=${groupId.value} date=$date")
        }
    }

    /** `start` — 명단이 없으면 만들고 한 번만 다시 부른다(§3.2-2). */
    private suspend fun start(userId: UserId, groupId: GroupId, date: LocalDate): MissionStartOutcome =
        withRoster(groupId, date, MissionStartOutcome.NoRoster) { progresses.startMission(userId, groupId, date) }

    /** Lua 를 부르고, [noRoster] 가 오면 명단을 만들고 한 번만 다시 부른다(§3.2-2 · §3.3-3). */
    private suspend fun <T> withRoster(groupId: GroupId, date: LocalDate, noRoster: T, call: suspend () -> T): T {
        val first = call()
        if (first != noRoster) return first
        // Lua 는 Redis 시각으로 07:00 을 넘겼는데 앱 시각은 아직 전(시계 차이 ms 창) — 07:00 전에는 명단을 만들지 않는다(§3.4-4)
        if (gameClock.now() < gameClock.startAt(date)) throw BusinessException(ErrorCode.GAME_NOT_OPEN)
        rosters.findOrCreate(groupId, date)
        return call()
    }

    /** 은행(§3.2-3) — 미리 만든 것이 있으면 PostgreSQL 을 읽지 않는다(D18). 없으면 Q2 → 뽑기 → `SET NX`, 지면 이긴 쪽 은행. */
    private suspend fun bankFor(userId: UserId, date: LocalDate, catalog: QuestionCatalog): QuestionBank {
        bankCache.get(userId, date)?.let { return it }
        val served = questions.findServedIds(userId)
        val bank = when (val draw = QuestionBankBuilder.build(catalog.idsByDifficulty, served, properties.bankSizePerDifficulty)) {
            is BankDraw.Built -> draw.bank
            is BankDraw.Exhausted -> throw BusinessException(ErrorCode.QUESTIONS_EXHAUSTED)
        }
        if (bankCache.putIfAbsent(userId, date, bank, gameClock.deadlineAt(date))) return bank
        // 동시 요청이 먼저 넣었다 — 은행은 하나다(ORACLE I2). 그사이 만료됐으면(08:00 직전) 방금 뽑은 것을 준다
        return bankCache.get(userId, date) ?: bank
    }
}
