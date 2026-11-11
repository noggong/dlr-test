package com.delightroom.mission.core.realtimeboard

import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.board.GroupResultCode
import com.delightroom.mission.core.domain.board.MemberState
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.core.testfixture.BoardFixtures.DOHYUN
import com.delightroom.mission.core.testfixture.BoardFixtures.HANA
import com.delightroom.mission.core.testfixture.BoardFixtures.JUNHO
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.OffsetDateTime

/** 그룹 Hash 파서(LLD realtime-board §2.2) — `HGETALL` 평탄 목록 · 채널 `hash` 객체. */
class GroupDaySnapshotTest {
    @Test
    fun `결과 전 Hash - 그룹원 필드와 빠진 필드는 null · false`() {
        val snapshot = GroupDaySnapshot.parse(DATE, BoardFixtures.inProgressHash())

        assertThat(snapshot.date).isEqualTo(DATE)
        assertThat(snapshot.version).isEqualTo(17L)
        assertThat(snapshot.pending).isFalse()
        assertThat(snapshot.result).isNull()
        assertThat(snapshot.decidedAt).isNull()
        assertThat(snapshot.rankingFinal).isFalse()
        assertThat(snapshot.ranking).isEmpty()
        assertThat(snapshot.members.keys).containsExactlyInAnyOrder(UserId(JUNHO), UserId(MINJI), UserId(DOHYUN), UserId(HANA))
        assertThat(snapshot.members[UserId(MINJI)]).isEqualTo(
            MemberState(UserId(MINJI), LoginId("minji"), MemberStatus.IN_PROGRESS, 2, false, BoardFixtures.MINJI_LS, 1),
        )
        assertThat(snapshot.members[UserId(DOHYUN)]).isEqualTo(
            MemberState(UserId(DOHYUN), LoginId("dohyun"), MemberStatus.NOT_STARTED, -1, false, null, null),
        )
    }

    @Test
    fun `결과 뒤 Hash - result · decidedAt · rankingFinal · ranking(key 순서 무관)`() {
        val fields = BoardFixtures.decidedHash(ranks = mapOf(MINJI to 1, JUNHO to 2, DOHYUN to 3, HANA to 4)).toMutableMap()
        fields["rankingFinal"] = "1"
        fields["pending"] = "1"
        val snapshot = GroupDaySnapshot.parse(DATE, fields)

        assertThat(snapshot.result).isEqualTo(GroupResultCode.FAILED_DEADLINE)
        assertThat(snapshot.decidedAt).isEqualTo(OffsetDateTime.parse("2026-11-12T08:00:00.000001+09:00").toInstant())
        assertThat(snapshot.rankingFinal).isTrue()
        assertThat(snapshot.pending).isTrue()
        assertThat(snapshot.ranking).isEqualTo(
            mapOf(UserId(MINJI) to 1, UserId(JUNHO) to 2, UserId(DOHYUN) to 3, UserId(HANA) to 4),
        )
    }

    @Test
    fun `HGETALL 평탄 목록과 객체는 같은 스냅샷`() {
        val fields = BoardFixtures.decidedHash()
        assertThat(GroupDaySnapshot.parseFlat(DATE, BoardFixtures.flat(fields))).isEqualTo(GroupDaySnapshot.parse(DATE, fields))
    }

    @Test
    fun `그룹원이 아닌 필드 · 모르는 필드는 무시한다`() {
        val fields = BoardFixtures.inProgressHash() + mapOf("#size" to "4", "105:cur" to "9", "extra" to "x")
        assertThat(GroupDaySnapshot.parse(DATE, fields).members).hasSize(4)
    }

    @Test
    fun `ranking id 가 숫자여도 읽는다`() {
        val fields = BoardFixtures.inProgressHash() + mapOf("result" to "SUCCEEDED", "ranking" to """[{"id":101,"r":1}]""")
        assertThat(GroupDaySnapshot.parse(DATE, fields).ranking).isEqualTo(mapOf(UserId(JUNHO) to 1))
    }

    @Test
    fun `µs 를 정밀도 그대로 Instant 로 - 음수도 내림`() {
        assertThat(microsToInstant(BoardFixtures.JUNHO_LS))
            .isEqualTo(OffsetDateTime.parse("2026-11-12T07:03:41.120384+09:00").toInstant())
        assertThat(microsToInstant(-1)).isEqualTo(Instant.parse("1969-12-31T23:59:59.999999Z"))
    }

    @Test
    fun `HGETALL 목록 길이가 홀수면 거부`() {
        assertThatThrownBy { GroupDaySnapshot.parseFlat(DATE, listOf("version", "1", "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
        "version,      ''",
        "version,      abc",
        "101:st,       DONE",
        "101:st,       ''",
        "101:p,        4",
        "101:p,        -2",
        "101:pn,       0",
        "101:ls,       x",
        "result,       FAILED",
        "decidedAt,    soon",
        "ranking,      '{\"id\":\"101\",\"r\":1}'",
        "ranking,      '[{\"id\":\"101\"}]'",
        "ranking,      '[{\"id\":\"101\",\"r\":0}]'",
        "ranking,      '[not json'",
    )
    fun `값이 틀리거나 필수 필드가 없으면 IllegalArgumentException`(field: String, value: String?) {
        val fields = BoardFixtures.inProgressHash().toMutableMap()
        if (value.isNullOrEmpty()) fields.remove(field) else fields[field] = value
        assertThatThrownBy { GroupDaySnapshot.parse(DATE, fields) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
