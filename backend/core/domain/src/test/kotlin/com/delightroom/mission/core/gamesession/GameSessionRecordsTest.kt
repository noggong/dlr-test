package com.delightroom.mission.core.gamesession

import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.GameSessionRecords
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.user.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Stream 칸 → game-session persister 레코드(LLD §3.7 · HLD platform §4.2). 끝에서 끝 판정은 worker `GameSessionEventHandlerTest`. */
class GameSessionRecordsTest {
    private val pk = "a".repeat(64)

    private val started = mapOf(
        "type" to "mission.started", "date" to "2030-01-15", "gid" to "7", "uid" to "11", "lid" to "alice_a",
        "version" to "3", "at" to "1894658400500000", "startedAt" to "1894658400500000", "late" to "1",
    )

    private val shown = mapOf(
        "type" to "question.shown", "date" to "2030-01-15", "gid" to "7", "uid" to "11", "lid" to "alice_a",
        "version" to "3", "at" to "1894658401000000", "questionNo" to "2", "questionId" to pk, "qid" to "42",
        "shownAt" to "1894658401000000",
    )

    @Test
    fun `O26 (바탕) Lua start 칸을 읽는다 — 시작 시각은 µs · 지각은 0 · 1`() {
        assertThat(GameSessionRecords.parse(started)).isEqualTo(
            MissionStartedRecord(LocalDate.of(2030, 1, 15), UserId(11), GroupId(7), Instant.parse("2030-01-14T22:00:00.500Z"), true),
        )
        assertThat((GameSessionRecords.parse(started + ("late" to "0")) as MissionStartedRecord).late).isFalse()
    }

    @Test
    fun `O25 (바탕) Lua shown 칸을 읽는다 — PK 는 운영 로그용 · qid 는 served_questions 용`() {
        assertThat(GameSessionRecords.parse(shown)).isEqualTo(
            QuestionShownRecord(LocalDate.of(2030, 1, 15), UserId(11), "alice_a", 2, pk, 42, Instant.parse("2030-01-14T22:00:01Z")),
        )
    }

    @Test
    fun `O26 (바탕) 칸이 없거나 틀리면 IllegalArgumentException`() {
        for (bad in listOf(
            started - "startedAt",
            started + ("late" to "yes"),
            started + ("date" to "2030/01/15"),
            shown + ("questionNo" to "4"),
            shown + ("questionNo" to "4294967297"),
            shown + ("questionId" to "XYZ"),
            shown + ("qid" to ""),
            shown + ("lid" to ""),
            started + ("type" to "solve.applied"),
        )) {
            assertThatThrownBy { GameSessionRecords.parse(bad) }.describedAs("$bad").isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
