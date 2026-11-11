package com.delightroom.mission.core.realtimeboard

import com.delightroom.mission.core.domain.board.Board
import com.delightroom.mission.core.domain.board.BoardEntry
import com.delightroom.mission.core.domain.board.BoardView
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.core.testfixture.BoardFixtures.DOHYUN
import com.delightroom.mission.core.testfixture.BoardFixtures.HANA
import com.delightroom.mission.core.testfixture.BoardFixtures.JUNHO
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalTime
import java.time.OffsetDateTime

/** ORACLE realtime-board O20 · O21 · O22 (+ I5 의 순수 함수 쪽) — 보는 사람별 규칙 · 순위 · 정렬 · 그룹 상태. */
class BoardViewTest {
    private val deadlineAt = DATE.atTime(8, 0).atZone(BoardFixtures.ZONE).toInstant()
    private val at0730 = DATE.atTime(7, 30).atZone(BoardFixtures.ZONE).toInstant()

    private fun build(fields: Map<String, String>, viewer: Long, now: java.time.Instant = at0730): Board =
        BoardView.build(GroupDaySnapshot.parse(DATE, fields), UserId(viewer), now, deadlineAt)

    private fun Board.entry(login: String): BoardEntry = entries.single { it.userId.value == login }

    private fun time(text: String) = OffsetDateTime.parse(text).toInstant()

    @Test
    fun `SPEC 3 응답 예시 - minji 가 보는 화면`() {
        val board = build(BoardFixtures.inProgressHash(), viewer = MINJI)

        assertThat(board.date).isEqualTo(DATE)
        assertThat(board.groupStatus).isEqualTo(GroupStatus.IN_PROGRESS)
        assertThat(board.version).isEqualTo(17L)
        assertThat(board.entries.map { listOf(it.userId.value, it.rank, it.progress, it.status, it.started, it.lastSolvedAt, it.passedAtQuestionNo) })
            .containsExactly(
                listOf("junho", 1, 2, MemberStatus.IN_PROGRESS, true, time("2026-11-12T07:03:41.120384+09:00"), null),
                listOf("minji", 2, 2, MemberStatus.IN_PROGRESS, true, time("2026-11-12T07:04:02.553001+09:00"), 1),
                listOf("dohyun", 3, 0, MemberStatus.NOT_STARTED, false, null, null),
                listOf("hana", 4, 1, MemberStatus.IN_PROGRESS, true, time("2026-11-12T07:02:10.000250+09:00"), null),
            )
    }

    @Test
    fun `O20 결과 없음 - 남의 부정 실패는 IN_PROGRESS 로 맨 아래, 본인에게는 CHEAT_FAILED`() {
        val byMinji = build(BoardFixtures.inProgressHash(), viewer = MINJI)
        val byHana = build(BoardFixtures.inProgressHash(), viewer = HANA)

        assertThat(byMinji.entry("hana")).extracting("status", "progress", "rank")
            .containsExactly(MemberStatus.IN_PROGRESS, 1, 4)
        assertThat(byHana.entry("hana")).extracting("status", "progress", "rank")
            .containsExactly(MemberStatus.CHEAT_FAILED, 1, 4)
        assertThat(byHana.entries.last().userId.value).isEqualTo("hana")

        // 다른 필드는 보는 사람과 상관없다
        assertThat(byHana.copy(entries = byHana.entries.map { it.copy(status = byMinji.entry(it.userId.value).status) }))
            .isEqualTo(byMinji)
    }

    @Test
    fun `O20 결과 있음 - 누가 보든 CHEAT_FAILED 이고 현황판이 같다`() {
        val byMinji = build(BoardFixtures.decidedHash(), viewer = MINJI)
        val byHana = build(BoardFixtures.decidedHash(), viewer = HANA)

        assertThat(byMinji.entry("hana").status).isEqualTo(MemberStatus.CHEAT_FAILED)
        assertThat(byHana.entry("hana").status).isEqualTo(MemberStatus.CHEAT_FAILED)
        assertThat(byHana).isEqualTo(byMinji)
        assertThat(byMinji.entries.map { it.status }.toSet())
            .isSubsetOf(MemberStatus.SUCCEEDED, MemberStatus.CHEAT_FAILED, MemberStatus.FAILED)
    }

    @Test
    fun `I5 결과 없는 현황판에서 CHEAT_FAILED 줄은 보는 사람 본인 줄뿐`() {
        val hash = BoardFixtures.inProgressHash().toMutableMap().apply { this["$JUNHO:st"] = "CHEAT_FAILED" }
        listOf(JUNHO, MINJI, DOHYUN, HANA).forEach { viewer ->
            val board = build(hash, viewer)
            val viewerLogin = hash.getValue("$viewer:lid")
            assertThat(board.entries.filter { it.status == MemberStatus.CHEAT_FAILED }.map { it.userId.value })
                .`as`("viewer $viewerLogin")
                .allMatch { it == viewerLogin }
        }
    }

    @Test
    fun `O21 진행도 -1 은 progress 0 · started false · NOT_STARTED · lastSolvedAt null`() {
        val dohyun = build(BoardFixtures.inProgressHash(), viewer = MINJI).entry("dohyun")

        assertThat(dohyun.progress).isEqualTo(0)
        assertThat(dohyun.started).isFalse()
        assertThat(dohyun.status).isEqualTo(MemberStatus.NOT_STARTED)
        assertThat(dohyun.lastSolvedAt).isNull()
        assertThat(dohyun.passedAtQuestionNo).isNull()
    }

    @Test
    fun `O21 진행도 0 은 started true`() {
        val hash = BoardFixtures.inProgressHash().toMutableMap().apply {
            this["$DOHYUN:st"] = "IN_PROGRESS"
            this["$DOHYUN:p"] = "0"
            this["$DOHYUN:late"] = "0"
        }
        assertThat(build(hash, viewer = MINJI).entry("dohyun")).extracting("progress", "started").containsExactly(0, true)
    }

    @Test
    fun `O22 동률은 같은 rank 이고 다음 번호를 건너뛰며 userId 사전순`() {
        val ls = BoardFixtures.micros(LocalTime.of(7, 10))
        // 입력 순서가 사전순과 반대가 되도록 zeta 를 먼저 둔다
        val hash = linkedMapOf(
            "version" to "3",
            "1:lid" to "zeta", "1:st" to "IN_PROGRESS", "1:p" to "2", "1:late" to "0", "1:ls" to "$ls",
            "2:lid" to "alpha", "2:st" to "IN_PROGRESS", "2:p" to "2", "2:late" to "0", "2:ls" to "$ls",
            "3:lid" to "mid", "3:st" to "IN_PROGRESS", "3:p" to "1", "3:late" to "0", "3:ls" to "$ls",
        )
        val board = build(hash, viewer = 3)

        assertThat(board.entries.map { it.userId.value to it.rank })
            .containsExactly("alpha" to 1, "zeta" to 1, "mid" to 3)
    }

    @Test
    fun `O22 결과가 있으면 rank 는 Hash ranking 값이다 - 계산값과 달라도`() {
        // 계산하면 junho 1 · minji 2 다. Hash 에는 반대로 넣는다
        val hash = BoardFixtures.decidedHash(ranks = mapOf(MINJI to 1, JUNHO to 2, DOHYUN to 3, HANA to 4))
        val board = build(hash, viewer = JUNHO)

        assertThat(board.entries.map { it.userId.value to it.rank })
            .containsExactly("minji" to 1, "junho" to 2, "dohyun" to 3, "hana" to 4)
    }

    @Test
    fun `O22 결과가 있어도 ranking 이 그룹원 전원을 담지 않으면 전원을 계산값으로`() {
        val hash = BoardFixtures.decidedHash(ranks = mapOf(MINJI to 1, JUNHO to 2)).toMutableMap()
        val board = build(hash, viewer = JUNHO)

        assertThat(board.entries.map { it.userId.value to it.rank })
            .containsExactly("junho" to 1, "minji" to 2, "dohyun" to 3, "hana" to 4)
    }

    @Test
    fun `결과가 있는데 ranking 이 아예 없으면 계산값 - 부정 실패는 누구에게나 CHEAT_FAILED 이고 맨 아래`() {
        val hash = BoardFixtures.decidedHash().toMutableMap().apply { remove("ranking") }
        listOf(MINJI, HANA).forEach { viewer ->
            val board = build(hash, viewer)
            val last = board.entries.last()
            assertThat(Triple(last.userId.value, last.status, last.rank)).isEqualTo(Triple("hana", MemberStatus.CHEAT_FAILED, 4))
        }
    }

    @Test
    fun `본인의 부정 실패도 맨 아래 - 다른 그룹원이 동률이어도`() {
        // hana 외 셋을 모두 미접속(진행도 -1 동률)으로 — 사전순으론 hana 가 앞이지만 부정 실패라 맨 아래
        val hash = BoardFixtures.inProgressHash().toMutableMap().apply {
            listOf(JUNHO, MINJI).forEach {
                this["$it:st"] = "NOT_STARTED"
                this["$it:p"] = "-1"
                remove("$it:ls")
                remove("$it:pn")
            }
        }
        val board = build(hash, viewer = HANA)

        assertThat(board.entries.map { Triple(it.userId.value, it.rank, it.status) }).containsExactly(
            Triple("dohyun", 1, MemberStatus.NOT_STARTED),
            Triple("junho", 1, MemberStatus.NOT_STARTED),
            Triple("minji", 1, MemberStatus.NOT_STARTED),
            Triple("hana", 4, MemberStatus.CHEAT_FAILED),
        )
    }

    @ParameterizedTest(name = "result={0} pending={1} now={2} → {3}")
    @CsvSource(
        "'',               '',  07:30:00,        IN_PROGRESS",
        "'',               '1', 07:30:00,        PENDING",
        "'',               '',  07:59:59.999999, IN_PROGRESS",
        "'',               '',  08:00:00,        PENDING",
        "SUCCEEDED,        '',  07:30:00,        SUCCEEDED",
        "FAILED_IMMEDIATE, '',  07:30:00,        FAILED",
        "FAILED_DEADLINE,  '',  08:00:01,        FAILED",
        "FAILED_DEADLINE,  '1', 08:00:01,        FAILED",
    )
    fun `O22 그룹 상태`(result: String?, pending: String?, now: LocalTime, expected: GroupStatus) {
        val hash = (if (result.isNullOrEmpty()) BoardFixtures.inProgressHash() else BoardFixtures.decidedHash(result = result))
            .toMutableMap()
        if (!pending.isNullOrEmpty()) hash["pending"] = pending
        val board = build(hash, viewer = MINJI, now = DATE.atTime(now).atZone(BoardFixtures.ZONE).toInstant())

        assertThat(board.groupStatus).isEqualTo(expected)
    }
}
