package com.delightroom.mission.core.gamesession

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.SignedUp
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.Group
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.progress.ShownWrite
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupDayRecord
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.RosterService
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** `RosterService.findOrCreate`(LLD game-session §3.4) — 저장소는 가짜로. ORACLE O2 의 끝에서 끝 판정은 api `GameInfoTest`. */
class RosterServiceTest {
    private val properties = GameProperties()
    private val gameClock = GameClock(properties, MutableClock(OffsetDateTime.parse("${D}T07:10:00+09:00").toInstant(), properties.zone))
    private val gid = GroupId(7)

    private class FakeRosters : RosterRepository {
        val stored = mutableMapOf<Pair<GroupId, LocalDate>, DayRoster>()
        var creates = 0

        override suspend fun find(gid: GroupId, date: LocalDate): DayRoster? = stored[gid to date]

        val restores = mutableListOf<GroupRestore?>()

        override suspend fun createIfAbsent(roster: DayRoster, restore: GroupRestore?): Boolean {
            creates++
            restores += restore
            return stored.putIfAbsent(roster.gid to roster.date, roster) == null
        }

        override suspend fun markConfirmed(date: LocalDate) = error("안 부른다")

        override suspend fun countUnmarked(date: LocalDate): Int = error("안 부른다")
    }

    private class FakeUsers(private val members: Map<UserId, LoginId>) : UserRepository {
        var cutoffs = mutableListOf<Instant>()

        override suspend fun findMembersJoinedBefore(groupIds: List<GroupId>, cutoff: Instant): Map<GroupId, Map<UserId, LoginId>> {
            cutoffs += cutoff
            return if (members.isEmpty()) emptyMap() else groupIds.associateWith { members }
        }

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

    private class FakeProgress(
        private val fail: Boolean = false,
        private val records: Map<GroupId, GroupDayRecord> = emptyMap(),
    ) : DailyProgressStore {
        val inserted = mutableListOf<Pair<LocalDate, List<Pair<UserId, GroupId>>>>()

        override suspend fun insertNotStarted(date: LocalDate, members: List<Pair<UserId, GroupId>>): Long {
            if (fail) error("파티션 없음")
            inserted += date to members
            return members.size.toLong()
        }

        override suspend fun ensurePartitions(date: LocalDate) = error("안 부른다")

        override suspend fun applyMissionStarted(record: MissionStartedRecord) = error("안 부른다")

        override suspend fun recordShown(record: QuestionShownRecord): ShownWrite = error("안 부른다")

        override suspend fun loadDayStates(date: LocalDate, gids: List<GroupId>): Map<GroupId, GroupDayRecord> =
            records.filterKeys { it in gids }
    }

    private val three = mapOf(UserId(1) to LoginId("carol_c"), UserId(2) to LoginId("alice_a"), UserId(3) to LoginId("bob_b"))

    @Test
    fun `O36 명단이 없으면 06시50분 전 가입자로 만들고 2명 이상이면 -1 행을 넣는다`(): Unit = runBlocking {
        val rosters = FakeRosters()
        val users = FakeUsers(three)
        val progress = FakeProgress()
        val service = RosterService(rosters, users, FakeGroups(), progress, gameClock)

        val roster = service.findOrCreate(gid, D)

        assertThat(roster.members).isEqualTo(three)
        assertThat(roster.name.value).isEqualTo("아침 수영")
        assertThat(roster.sortedLoginIds().map { it.value }).containsExactly("alice_a", "bob_b", "carol_c")
        assertThat(users.cutoffs).containsExactly(OffsetDateTime.parse("${D}T06:50:00+09:00").toInstant())
        assertThat(progress.inserted).containsExactly(D to three.keys.map { it to gid })
    }

    @Test
    fun `O2 (바탕) 명단이 있으면 PostgreSQL 을 읽지 않고 그것을 준다`(): Unit = runBlocking {
        val rosters = FakeRosters()
        val existing = DayRoster(gid, D, GroupName("아침 수영"), mapOf(UserId(9) to LoginId("only_one")))
        rosters.stored[gid to D] = existing
        val users = FakeUsers(three)

        val roster = RosterService(rosters, users, FakeGroups(), FakeProgress(), gameClock).findOrCreate(gid, D)

        assertThat(roster).isSameAs(existing)
        assertThat(users.cutoffs).isEmpty()
        assertThat(rosters.creates).isZero()
    }

    @Test
    fun `O2 (바탕) 1명 · 0명 그룹은 명단만 만들고 -1 행을 넣지 않는다`(): Unit = runBlocking {
        for (members in listOf(mapOf(UserId(1) to LoginId("solo_s")), emptyMap())) {
            val progress = FakeProgress()
            val roster = RosterService(FakeRosters(), FakeUsers(members), FakeGroups(), progress, gameClock).findOrCreate(gid, D)

            assertThat(roster.size).isEqualTo(members.size)
            assertThat(progress.inserted).isEmpty()
        }
    }

    @Test
    fun `O2 (바탕) -1 행이 실패해도 명단은 돌려준다 — worker member 가 다시 넣는다`(): Unit = runBlocking {
        val roster = RosterService(FakeRosters(), FakeUsers(three), FakeGroups(), FakeProgress(fail = true), gameClock).findOrCreate(gid, D)

        assertThat(roster.size).isEqualTo(3)
    }

    @Test
    fun `O2 (바탕) 다른 쪽이 먼저 만들었으면(created 0) 저장된 명단을 주고 -1 행은 넣지 않는다`(): Unit = runBlocking {
        val first = DayRoster(gid, D, GroupName("아침 수영"), three)
        val rosters = object : RosterRepository {
            var calls = 0

            override suspend fun find(gid: GroupId, date: LocalDate): DayRoster? = if (calls++ == 0) null else first

            override suspend fun createIfAbsent(roster: DayRoster, restore: GroupRestore?): Boolean = false

            override suspend fun markConfirmed(date: LocalDate) = error("안 부른다")

            override suspend fun countUnmarked(date: LocalDate): Int = error("안 부른다")
        }
        val progress = FakeProgress()

        val roster = RosterService(rosters, FakeUsers(three), FakeGroups(), progress, gameClock).findOrCreate(gid, D)

        assertThat(roster).isSameAs(first)
        assertThat(progress.inserted).isEmpty()
    }

    @Test
    fun `O1 (바탕) 07시 전에는 명단을 만들지 않는다 — 있으면 읽기만`(): Unit = runBlocking {
        val early = GameClock(properties, MutableClock(OffsetDateTime.parse("${D}T06:59:59.999+09:00").toInstant(), properties.zone))
        val rosters = FakeRosters()
        val service = RosterService(rosters, FakeUsers(three), FakeGroups(), FakeProgress(), early)

        assertThatThrownBy { runBlocking { service.findOrCreate(gid, D) } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(rosters.creates).isZero()
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
    }
}
