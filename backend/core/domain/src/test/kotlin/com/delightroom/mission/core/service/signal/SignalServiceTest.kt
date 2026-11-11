package com.delightroom.mission.core.service.signal

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.SignedUp
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.progress.FailCommand
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.PassCommand
import com.delightroom.mission.core.domain.progress.PassSignal
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.progress.ShownWrite
import com.delightroom.mission.core.domain.progress.SignalOutcome
import com.delightroom.mission.core.domain.progress.SignalRejection
import com.delightroom.mission.core.domain.progress.SignalReply
import com.delightroom.mission.core.domain.progress.SignalResult
import com.delightroom.mission.core.domain.progress.SignalTarget
import com.delightroom.mission.core.domain.progress.SolveCommand
import com.delightroom.mission.core.domain.progress.SolveSignal
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupDayRecord
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.RosterService
import com.delightroom.mission.core.service.SignalService
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * SignalService(LLD solve-signal §3.0 · §3.4) — `NO_ROSTER` 처리 · 거부 코드 변환 · 시간 창 계산. 저장소는 가짜로.
 * HTTP 끝에서 끝 판정은 api `SolveApiTest` · `PassApiTest` · `FailureApiTest`.
 */
class SignalServiceTest {
    private val properties = GameProperties()
    private val clock = MutableClock(at("07:10:00"), properties.zone)
    private val gameClock = GameClock(properties, clock)
    private val uid = UserId(11)
    private val gid = GroupId(7)
    private val q1 = QuestionId("a".repeat(64))
    private val solve = SolveSignal(1, q1, "${D}T07:09:59+09:00", at("07:09:59"))
    private val pass = PassSignal(1, q1)

    /** Lua 대신 — 부를 때마다 [replies] 의 다음 답을 준다. 받은 명령을 모은다. */
    private class ScriptedProgress(vararg replies: SignalReply) : ProgressRepository {
        private val queue = ArrayDeque(replies.toList())
        val targets = mutableListOf<SignalTarget>()
        val calls = mutableListOf<Any>()

        private fun next(target: SignalTarget, command: Any): SignalReply {
            targets += target
            calls += command
            return queue.removeFirst()
        }

        override suspend fun applySolve(command: SolveCommand) = next(command.target, command)
        override suspend fun applyPass(command: PassCommand) = next(command.target, command)
        override suspend fun applyCheatFail(command: FailCommand) = next(command.target, command)
        override suspend fun find(userId: UserId, date: LocalDate): MissionProgress? = error("안 부른다")
        override suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress> = error("안 부른다")
        override suspend fun save(progress: MissionProgress): MissionProgress = error("안 부른다")
        override suspend fun startMission(userId: UserId, groupId: GroupId, date: LocalDate): MissionStartOutcome = error("안 부른다")
        override suspend fun markShown(
            userId: UserId,
            groupId: GroupId,
            date: LocalDate,
            questionNo: Int,
            questionId: QuestionId,
            internalId: Long?,
        ): ShownOutcome = error("안 부른다")
    }

    private class FakeRosters : RosterRepository {
        var creates = 0

        override suspend fun find(gid: GroupId, date: LocalDate): DayRoster? = null

        override suspend fun createIfAbsent(roster: DayRoster, restore: GroupRestore?): Boolean {
            creates++
            return true
        }

        override suspend fun markConfirmed(date: LocalDate) = error("안 부른다")

        override suspend fun countUnmarked(date: LocalDate): Int = error("안 부른다")
    }

    private class FakeUsers : UserRepository {
        override suspend fun findMembersJoinedBefore(groupIds: List<GroupId>, cutoff: Instant): Map<GroupId, Map<UserId, LoginId>> =
            groupIds.associateWith { mapOf(UserId(11) to LoginId("alice_a"), UserId(12) to LoginId("bob_b")) }

        override suspend fun findById(id: UserId): User? = error("안 부른다")
        override suspend fun findByLoginId(loginId: LoginId): User? = error("안 부른다")
        override suspend fun save(user: User): User = error("안 부른다")
        override suspend fun signUp(loginId: LoginId, passwordHash: String, choice: GroupChoice, maxGroupSize: Int): SignedUp = error("안 부른다")
        override suspend fun findAccountByLoginId(loginId: LoginId): Account? = error("안 부른다")
        override suspend fun findAccountById(id: UserId): Account? = error("안 부른다")
        override suspend fun findIdsJoinedIn(from: Instant?, to: Instant, after: UserId?, limit: Int): List<UserId> = error("안 부른다")
    }

    private class FakeGroups : GroupRepository {
        override suspend fun findName(id: GroupId): GroupName = GroupName("아침 수영")
        override suspend fun findById(id: GroupId): Group? = error("안 부른다")
        override suspend fun findByName(name: GroupName): Group? = error("안 부른다")
        override suspend fun create(name: GroupName): Group = error("안 부른다")
        override suspend fun findPage(afterId: GroupId?, limit: Int): List<Pair<GroupId, GroupName>> = error("안 부른다")
    }

    private class FakeDaily : DailyProgressStore {
        override suspend fun insertNotStarted(date: LocalDate, members: List<Pair<UserId, GroupId>>): Long = members.size.toLong()
        override suspend fun ensurePartitions(date: LocalDate) = error("안 부른다")
        override suspend fun applyMissionStarted(record: MissionStartedRecord) = error("안 부른다")
        override suspend fun recordShown(record: QuestionShownRecord): ShownWrite = error("안 부른다")
        override suspend fun loadDayStates(date: LocalDate, gids: List<GroupId>): Map<GroupId, GroupDayRecord> = emptyMap()
    }

    private val rosters = FakeRosters()

    private fun service(progress: ProgressRepository) =
        SignalService(progress, RosterService(rosters, FakeUsers(), FakeGroups(), FakeDaily(), gameClock), gameClock, properties)

    private fun ok(progress: Int = 1, status: MemberStatus = MemberStatus.IN_PROGRESS, passNo: Int? = null, outcome: SignalOutcome = SignalOutcome.APPLIED) =
        SignalResult(outcome, progress, status, GroupStatus.IN_PROGRESS, 3, at("07:10:00"), passNo)

    private fun rejected(code: String, t: String = "07:10:00") = SignalRejection(code, at(t))

    private fun codeOf(block: suspend () -> Unit): ErrorCode? =
        try {
            runBlocking { block() }
            null
        } catch (e: BusinessException) {
            e.errorCode
        }

    @Test
    fun `O9 07시 전 명단 없음 — 풀이 · 패스는 GAME_NOT_OPEN 이고 명단을 만들지 않는다`() {
        clock.set(at("06:59:59"))
        val progress = ScriptedProgress(rejected("NO_ROSTER", "06:59:59"), rejected("NO_ROSTER", "06:59:59"))
        val service = service(progress)

        assertThat(codeOf { service.solve(uid, gid, solve) }).isEqualTo(ErrorCode.GAME_NOT_OPEN)
        assertThat(codeOf { service.pass(uid, gid, pass) }).isEqualTo(ErrorCode.GAME_NOT_OPEN)
        assertThat(progress.calls).hasSize(2)
        assertThat(rosters.creates).isZero()
    }

    @Test
    fun `O8 07시 전 명단 없음 — 실패 신호는 MISSION_NOT_STARTED 이고 명단을 만들지 않는다`() {
        clock.set(at("06:59:00"))
        val progress = ScriptedProgress(rejected("NO_ROSTER", "06:59:00"))

        assertThat(codeOf { service(progress).failByCheat(uid, gid) }).isEqualTo(ErrorCode.MISSION_NOT_STARTED)
        assertThat(progress.calls).hasSize(1)
        assertThat(rosters.creates).isZero()
    }

    @Test
    fun `O9 (바탕) Lua 시각은 07시 이후여도 앱 시계가 07시 전이면 명단을 만들지 않고 GAME_NOT_OPEN`() {
        clock.set(at("06:59:59.900"))
        val progress = ScriptedProgress(rejected("NO_ROSTER", "07:00:00.100"))

        assertThat(codeOf { service(progress).solve(uid, gid, solve) }).isEqualTo(ErrorCode.GAME_NOT_OPEN)
        assertThat(rosters.creates).isZero()
    }

    @Test
    fun `O8 (바탕) 07시 이후 명단 없음 — 명단을 만들고 Lua 를 한 번 더 불러 그 답을 준다`(): Unit = runBlocking {
        val progress = ScriptedProgress(rejected("NO_ROSTER"), ok(progress = 1))

        val result = service(progress).solve(uid, gid, solve)

        assertThat(result.progress).isEqualTo(1)
        assertThat(rosters.creates).isEqualTo(1)
        assertThat(progress.calls).hasSize(2)
        assertThat(progress.targets.distinct()).hasSize(1)
    }

    @Test
    fun `O6 · O7 · O8 명단을 만든 뒤의 거부는 그 코드 그대로 — NOT_PARTICIPANT · SOLO_GROUP · MISSION_NOT_STARTED`() {
        for (code in listOf(ErrorCode.NOT_PARTICIPANT, ErrorCode.SOLO_GROUP, ErrorCode.MISSION_NOT_STARTED)) {
            val solveRepo = ScriptedProgress(rejected("NO_ROSTER"), rejected(code.name))
            val passRepo = ScriptedProgress(rejected("NO_ROSTER"), rejected(code.name))
            val failRepo = ScriptedProgress(rejected("NO_ROSTER"), rejected(code.name))

            assertThat(codeOf { service(solveRepo).solve(uid, gid, solve) }).isEqualTo(code)
            assertThat(codeOf { service(passRepo).pass(uid, gid, pass) }).isEqualTo(code)
            assertThat(codeOf { service(failRepo).failByCheat(uid, gid) }).isEqualTo(code)
        }
    }

    @Test
    fun `O8 (바탕) 08시 이후 명단 없음 — 명단을 만들지 않고 세 신호 모두 MISSION_NOT_STARTED`() {
        // 앱 시각 · Lua t 중 하나라도 08:00 이후면 만들지 않는다
        for ((app, lua) in listOf("08:00:00" to "08:00:00", "07:59:59.900" to "08:00:00.100", "08:00:00.100" to "07:59:59.900", "10:00:00" to "10:00:00")) {
            clock.set(at(app))
            val solveRepo = ScriptedProgress(rejected("NO_ROSTER", lua))
            val passRepo = ScriptedProgress(rejected("NO_ROSTER", lua))
            val failRepo = ScriptedProgress(rejected("NO_ROSTER", lua))

            assertThat(codeOf { service(solveRepo).solve(uid, gid, solve) }).describedAs("$app $lua").isEqualTo(ErrorCode.MISSION_NOT_STARTED)
            assertThat(codeOf { service(passRepo).pass(uid, gid, pass) }).isEqualTo(ErrorCode.MISSION_NOT_STARTED)
            assertThat(codeOf { service(failRepo).failByCheat(uid, gid) }).isEqualTo(ErrorCode.MISSION_NOT_STARTED)
            assertThat(solveRepo.calls + passRepo.calls + failRepo.calls).hasSize(3)
        }
        assertThat(rosters.creates).isZero()
    }

    @Test
    fun `O8 (바탕) 07_59_59 에 명단 없음은 아직 만든다`(): Unit = runBlocking {
        clock.set(at("07:59:59"))
        val progress = ScriptedProgress(rejected("NO_ROSTER", "07:59:59"), ok())

        service(progress).solve(uid, gid, solve)

        assertThat(rosters.creates).isEqualTo(1)
    }

    @Test
    fun `O8 (바탕) 명단을 만든 뒤에도 NO_ROSTER 면 MISSION_NOT_STARTED — 세 번 부르지 않는다`() {
        val progress = ScriptedProgress(rejected("NO_ROSTER"), rejected("NO_ROSTER"))

        assertThat(codeOf { service(progress).pass(uid, gid, pass) }).isEqualTo(ErrorCode.MISSION_NOT_STARTED)
        assertThat(progress.calls).hasSize(2)
    }

    @Test
    fun `O6 ~ O9 (바탕) Lua 거부 코드는 같은 이름의 ErrorCode 로`() {
        val codes = listOf(
            ErrorCode.NOT_PARTICIPANT, ErrorCode.SOLO_GROUP, ErrorCode.MISSION_NOT_STARTED, ErrorCode.MISSION_ALREADY_FINISHED,
            ErrorCode.GAME_NOT_OPEN, ErrorCode.SUBMISSION_CLOSED, ErrorCode.QUESTION_OUT_OF_ORDER, ErrorCode.QUESTION_MISMATCH,
            ErrorCode.PASS_ALREADY_USED,
        )
        for (code in codes) {
            val progress = ScriptedProgress(rejected(code.name))
            assertThat(codeOf { service(progress).solve(uid, gid, solve) }).isEqualTo(code)
            assertThat(progress.calls).hasSize(1)
        }
        assertThat(rosters.creates).isZero()
    }

    @Test
    fun `O6 ~ O9 (바탕) 모르는 거부 코드는 계약 위반 — 오류 코드로 바꾸지 않는다`() {
        val progress = ScriptedProgress(rejected("UNAUTHORIZED"))

        assertThatThrownBy { runBlocking { service(progress).solve(uid, gid, solve) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("UNAUTHORIZED")
    }

    @Test
    fun `O1 (바탕) 받아들인 신호는 Lua 의 답 그대로 — 재시도도 같다`(): Unit = runBlocking {
        val applied = ok(progress = 2)
        val replay = ok(progress = 2, outcome = SignalOutcome.REPLAY)
        val progress = ScriptedProgress(applied, replay)
        val service = service(progress)

        assertThat(service.solve(uid, gid, solve)).isEqualTo(applied)
        assertThat(service.solve(uid, gid, solve)).isEqualTo(replay)
        assertThat(progress.calls).containsExactly(SolveCommand(progress.targets[0], solve), SolveCommand(progress.targets[0], solve))
    }

    @Test
    fun `O18 (바탕) 패스는 패스 번호를 함께 준다`(): Unit = runBlocking {
        val progress = ScriptedProgress(ok(progress = 0, passNo = 1))

        assertThat(service(progress).pass(uid, gid, pass).passedAtQuestionNo).isEqualTo(1)
        assertThat(progress.calls.single()).isEqualTo(PassCommand(progress.targets.single(), pass))
    }

    @Test
    fun `O10 (바탕) 시간 창 — KST 날짜의 07_00 · 08_00 · 08_00_03 을 epoch µs 로, uid · gid 는 그대로`(): Unit = runBlocking {
        clock.set(at("07:59:59.999"))
        val progress = ScriptedProgress(ok(status = MemberStatus.CHEAT_FAILED))

        service(progress).failByCheat(uid, gid)

        val target = progress.targets.single()
        assertThat(target.userId).isEqualTo(uid)
        assertThat(target.groupId).isEqualTo(gid)
        assertThat(target.date).isEqualTo(D)
        assertThat(target.openAtUs).isEqualTo(us("07:00:00"))
        assertThat(target.deadlineAtUs).isEqualTo(us("08:00:00"))
        assertThat(target.graceEndAtUs).isEqualTo(us("08:00:03"))
        assertThat(progress.calls.single()).isEqualTo(FailCommand(target))
    }

    @Test
    fun `O10 (바탕) 날짜는 game clock-offset 을 더한 앱 시각의 KST 날짜다`(): Unit = runBlocking {
        // 실제 시계는 전날 23:00 KST, 오프셋 +8시간 10분 → 게임 시각 D 07:10
        val shifted = GameProperties(clockOffset = java.time.Duration.ofMinutes(8 * 60 + 10))
        clock.set(at("07:10:00").minus(shifted.clockOffset))
        val progress = ScriptedProgress(ok())
        SignalService(progress, RosterService(rosters, FakeUsers(), FakeGroups(), FakeDaily(), GameClock(shifted, clock)), GameClock(shifted, clock), shifted)
            .solve(uid, gid, solve)

        assertThat(progress.targets.single().date).isEqualTo(D)
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)

        fun at(time: String): Instant = OffsetDateTime.parse("${D}T$time+09:00").toInstant()

        fun us(time: String): Long = GameClock.epochMicros(at(time))
    }
}
