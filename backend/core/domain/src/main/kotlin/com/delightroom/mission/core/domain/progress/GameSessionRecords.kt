package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** `worker: persister` 가 PostgreSQL 에 남길 game-session 이벤트(LLD game-session §3.7 · HLD platform §4.2). */
sealed interface GameSessionRecord

/** `mission.started` — Lua `start` 가 진행도 -1 → 0 · 시작 시각 · 지각을 고정했다(Q11). */
data class MissionStartedRecord(
    val date: LocalDate,
    val userId: UserId,
    val groupId: GroupId,
    val startedAt: Instant,
    val late: Boolean,
) : GameSessionRecord

/** `question.shown` — Lua `shown` 이 번호 [questionNo] 의 지금 문제를 고정했다(Q12). */
data class QuestionShownRecord(
    val date: LocalDate,
    val userId: UserId,
    /** 운영 로그용 로그인 아이디(`lid`). */
    val loginId: String,
    val questionNo: Int,
    /** 문제 PK(`questions.pk_hash` — hex 64). 운영 로그에 쓴다. */
    val questionPk: String,
    /** `questions.id`(`qid`) — `served_questions.question_id`. */
    val questionId: Long,
    val shownAt: Instant,
) : GameSessionRecord

/** Q12 의 결과 — 운영 로그를 쓸지 처리기가 고른다. */
sealed interface ShownWrite {
    /** 새로 넣었다. */
    data object Inserted : ShownWrite

    /** 같은 (날짜, 번호)의 행이 이미 있다 — 같은 이벤트의 재처리. */
    data object Replayed : ShownWrite

    /** 다른 (날짜, 번호)로 이미 낸 문제 — 출제 이력 중복(D15). 처음 기록은 그대로 둔다. */
    data class Duplicate(val firstDate: LocalDate, val firstQuestionNo: Int) : ShownWrite
}

/** Stream 칸(값은 모두 문자열 — HLD platform §4.2) → [GameSessionRecord]. 칸이 없거나 형식이 틀리면 [IllegalArgumentException]. */
object GameSessionRecords {
    const val MISSION_STARTED = "mission.started"
    const val QUESTION_SHOWN = "question.shown"

    val TYPES: Set<String> = setOf(MISSION_STARTED, QUESTION_SHOWN)

    private val PK = Regex("[0-9a-f]{64}")

    fun parse(fields: Map<String, String>): GameSessionRecord {
        fun req(name: String): String = requireNotNull(fields[name]) { "$name 칸 없음" }
        fun long(name: String): Long = requireNotNull(req(name).toLongOrNull()) { "$name 은 정수: ${fields[name]}" }
        val date = try {
            LocalDate.parse(req("date"))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("date 는 YYYY-MM-DD: ${fields["date"]}", e)
        }
        return when (val type = req("type")) {
            MISSION_STARTED -> MissionStartedRecord(
                date = date,
                userId = UserId(long("uid")),
                groupId = GroupId(long("gid")),
                startedAt = microsToInstant(long("startedAt")),
                late = when (req("late")) {
                    "1" -> true
                    "0" -> false
                    else -> throw IllegalArgumentException("late 는 0 · 1: ${fields["late"]}")
                },
            )
            QUESTION_SHOWN -> QuestionShownRecord(
                date = date,
                userId = UserId(long("uid")),
                loginId = req("lid").also { require(it.isNotEmpty()) { "lid 가 비었다" } },
                questionNo = requireNotNull(req("questionNo").toIntOrNull()?.takeIf { it in 1..3 }) { "questionNo 는 1~3: ${fields["questionNo"]}" },
                questionPk = req("questionId").also { require(PK.matches(it)) { "questionId 는 hex 64: $it" } },
                questionId = long("qid"),
                shownAt = microsToInstant(long("shownAt")),
            )
            else -> throw IllegalArgumentException("이 기능의 type 이 아님: $type")
        }
    }
}
