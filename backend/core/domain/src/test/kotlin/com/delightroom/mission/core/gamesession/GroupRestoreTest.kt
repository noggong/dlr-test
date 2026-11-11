package com.delightroom.mission.core.gamesession

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupDayRecord
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.roster.MemberDayRecord
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** 재적재의 칸 계산(ORACLE platform O19 — LLD platform §4.1 칸 매핑 · 재적재 판정 · `version` · `rankingFinal`). */
class GroupRestoreTest {
    private val roster = DayRoster(
        GroupId(7), D, GroupName("아침 수영"),
        mapOf(UserId(1) to LoginId("alice_a"), UserId(2) to LoginId("bob_b"), UserId(3) to LoginId("carol_c")),
    )
    private val now = at("07:30:00.123456")
    private val deadline = at("08:00:00")

    private fun us(t: Instant) = GameClock.epochMicros(t).toString()

    @Test
    fun `O19 기록이 없으면 null — 처음 만드는 명단은 version 0 · -1 그대로`() {
        assertThat(GroupRestore.of(roster, null, now, deadline)).isNull()
        assertThat(GroupRestore.of(roster, GroupDayRecord(emptyList()), now, deadline)).isNull()
    }

    @Test
    fun `O19 그룹원 칸 매핑 — 시작 전 행은 p · st 만, 진행 중은 sa · late · ls · pn · pq · cur · cf`() {
        val started = at("07:10:00.000001")
        val solved = at("07:12:00.654321")
        val cheat = at("07:20:00.5")
        val record = GroupDayRecord(
            listOf(
                MemberDayRecord(UserId(1), -1, "NOT_STARTED"),
                MemberDayRecord(
                    UserId(2), 1, "IN_PROGRESS", started, true, solved, passNo = 2, passedPk = "p".repeat(64),
                    solvedNo = 1, solvedAt = solved, currentPk = "c".repeat(64),
                ),
                MemberDayRecord(UserId(3), 0, "CHEAT_FAILED", started, false, cheatFailedAt = cheat, currentPk = "d".repeat(64)),
                // 명단에 없는 행(07:00 뒤 가입)은 버린다
                MemberDayRecord(UserId(99), 0, "IN_PROGRESS", started, false),
            ),
        )

        val restore = GroupRestore.of(roster, record, now, deadline)!!

        assertThat(restore.version).isEqualTo(now.toEpochMilli())
        assertThat(restore.rerank).isFalse()
        assertThat(restore.fields).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "1:p" to "-1", "1:st" to "NOT_STARTED",
                "2:p" to "1", "2:st" to "IN_PROGRESS", "2:sa" to us(started), "2:late" to "1", "2:ls" to us(solved),
                "2:pn" to "2", "2:pq" to "p".repeat(64), "2:cur" to "c".repeat(64),
                "3:p" to "0", "3:st" to "CHEAT_FAILED", "3:sa" to us(started), "3:late" to "0", "3:cf" to us(cheat), "3:cur" to "d".repeat(64),
            ),
        )
    }

    @Test
    fun `O19 solve_signals 가 앞서 있으면 그 번호 · 도착 시각 — 3 이면 SUCCEEDED`() {
        val started = at("07:10:00")
        val third = at("07:25:00.000007")
        val record = GroupDayRecord(
            listOf(
                MemberDayRecord(
                    UserId(1), 2, "IN_PROGRESS", started, false, at("07:20:00"),
                    solvedNo = 3, solvedAt = third, currentPk = "c".repeat(64),
                ),
            ),
        )

        val fields = GroupRestore.of(roster, record, now, deadline)!!.fields

        assertThat(fields).containsEntry("1:p", "3").containsEntry("1:st", "SUCCEEDED").containsEntry("1:ls", us(third))
        assertThat(fields).doesNotContainKey("1:cur")
    }

    @Test
    fun `O19 결과 — 08시 전 확정은 rankingFinal 1, 마감 확정은 0 · 둘 다 랭킹을 다시 계산`() {
        val members = listOf(MemberDayRecord(UserId(1), 3, "SUCCEEDED", at("07:10:00"), false, at("07:20:00")))

        val early = GroupRestore.of(roster, GroupDayRecord(members, "SUCCEEDED", at("07:40:00.000001")), now, deadline)!!
        val late = GroupRestore.of(roster, GroupDayRecord(members, "FAILED_DEADLINE", deadline), now, deadline)!!
        // 행이 없어도 결과가 있으면 재적재다
        val resultOnly = GroupRestore.of(roster, GroupDayRecord(emptyList(), "FAILED_IMMEDIATE", at("07:50:00")), now, deadline)!!

        assertThat(early.rerank).isTrue()
        assertThat(early.fields).containsEntry("result", "SUCCEEDED").containsEntry("decidedAt", us(at("07:40:00.000001")))
            .containsEntry("rankingFinal", "1")
        assertThat(late.fields).containsEntry("result", "FAILED_DEADLINE").containsEntry("rankingFinal", "0")
        assertThat(resultOnly.fields).containsOnlyKeys("result", "decidedAt", "rankingFinal")
        assertThat(early.fields).doesNotContainKey("pending")
    }

    @Test
    fun `O19 규칙 밖 값은 올리지 않는다`() {
        fun bad(m: MemberDayRecord, result: String? = null) =
            assertThatThrownBy { GroupRestore.of(roster, GroupDayRecord(listOf(m), result, now), now, deadline) }
                .isInstanceOf(IllegalArgumentException::class.java)

        bad(MemberDayRecord(UserId(1), 0, "DONE"))
        bad(MemberDayRecord(UserId(1), 4, "IN_PROGRESS"))
        bad(MemberDayRecord(UserId(1), 0, "IN_PROGRESS"), result = "NO_RESULT")
        // 진행도 ≥ 1 인데 마지막 풀이 시각이 없으면 랭킹을 다시 계산할 수 없다
        bad(MemberDayRecord(UserId(1), 1, "IN_PROGRESS", at("07:10:00"), false))
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2030, 1, 15)

        fun at(time: String): Instant = OffsetDateTime.parse("${D}T$time+09:00").toInstant()
    }
}
