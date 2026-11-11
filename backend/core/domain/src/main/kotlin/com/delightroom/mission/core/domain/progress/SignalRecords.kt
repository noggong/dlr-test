package com.delightroom.mission.core.domain.progress

import com.delightroom.mission.core.domain.board.GroupResultCode
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.GroupResultState
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.result.RankedMember
import com.delightroom.mission.core.domain.result.toGroupStatus
import com.delightroom.mission.core.domain.user.UserId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** `worker: persister` 가 PostgreSQL 에 남길 이 기능의 이벤트(LLD solve-signal §2.2 · §2.3). */
sealed interface SignalRecord

/** `solve.applied` — 문제 [questionNo] 를 맞혔다. 반영 뒤 진행도는 [questionNo] 다. */
data class SolveRecord(
    val date: LocalDate,
    val userId: UserId,
    val questionNo: Int,
    /** 문제 PK(`questions.pk_hash` — hex 64). */
    val questionPk: String,
    /** 클라이언트가 보낸 맞힌 시각 — 기록만(R-S5). */
    val successTime: Instant,
    val clickedAt: Instant,
    val arrivedAt: Instant,
) : SignalRecord

/** `pass.used` — 문제 [questionNo] 를 패스로 넘겼다. */
data class PassRecord(
    val date: LocalDate,
    val userId: UserId,
    val questionNo: Int,
    val questionPk: String,
    val at: Instant,
) : SignalRecord

/** `member.cheat_failed` — 부정 실패. */
data class CheatFailRecord(
    val date: LocalDate,
    val userId: UserId,
    val at: Instant,
) : SignalRecord

/**
 * 그룹 결과 이벤트 공통(`group.decided` · `group.ranking_final`) — 결과 · 그 순간의 랭킹 스냅샷.
 * [members] 는 `group_result_members` 행(uid), [display] 는 같은 랭킹 JSON 을 결과 캐시 모양(로그인 아이디 · 표시 순서)으로 읽은 것이다.
 * [display] 는 캐시에만 쓰므로 읽지 못하면 null 이다 — 정본 기록(PostgreSQL)을 캐시 모양 해석에 묶지 않는다.
 */
sealed interface GroupResultRecord : SignalRecord {
    val date: LocalDate
    val groupId: GroupId
    val result: GroupResultCode
    val decidedAt: Instant
    val rankingFinal: Boolean
    val members: List<ResultMemberRecord>
    val display: List<RankedMember>?

    /** 결과 캐시 값(LLD deadline-result §2.4 · §3.4) — [display] 를 읽지 못했으면 null. */
    fun toLatest(): LatestResult? = display?.let { LatestResult(date, result.toGroupStatus(), decidedAt, rankingFinal, it) }
}

/** `group.decided` — 그룹 결과 확정 · 그 순간의 랭킹 스냅샷(줄 수 = [memberCount]). */
data class GroupDecidedRecord(
    override val date: LocalDate,
    override val groupId: GroupId,
    override val result: GroupResultCode,
    override val decidedAt: Instant,
    val memberCount: Int,
    override val members: List<ResultMemberRecord>,
    override val rankingFinal: Boolean,
    override val display: List<RankedMember>?,
) : GroupResultRecord

/**
 * `group.ranking_final` — 08:00:03 랭킹 확정(LLD deadline-result §4.1 6.2). 결과 · `decidedAt` 은 `group.decided` 와 같고
 * 랭킹만 유예 반영분으로 바뀐다. `memberCount` 칸은 없다 — 줄 수가 그룹원 수다.
 */
data class GroupRankingFinalRecord(
    override val date: LocalDate,
    override val groupId: GroupId,
    override val result: GroupResultCode,
    override val decidedAt: Instant,
    override val members: List<ResultMemberRecord>,
    override val display: List<RankedMember>?,
) : GroupResultRecord {
    override val rankingFinal: Boolean get() = true
}

/** 랭킹 JSON 한 줄(LLD deadline-result §2.2) → `group_result_members` 한 행. */
data class ResultMemberRecord(
    val userId: UserId,
    val rank: Int,
    /** -1 ~ 3. */
    val progress: Int,
    val status: MemberStatus,
    val lastSolvedAt: Instant?,
    val passNo: Int?,
) {
    val started: Boolean get() = progress >= 0
    val cheatFailed: Boolean get() = status == MemberStatus.CHEAT_FAILED
}

/** Stream 칸(값은 모두 문자열 — HLD platform §4.2) → [SignalRecord]. 칸이 없거나 형식이 틀리면 [IllegalArgumentException]. */
object SignalRecords {
    const val SOLVE_APPLIED = "solve.applied"
    const val PASS_USED = "pass.used"
    const val MEMBER_CHEAT_FAILED = "member.cheat_failed"
    const val GROUP_DECIDED = "group.decided"
    const val GROUP_RANKING_FINAL = "group.ranking_final"

    val TYPES: Set<String> = setOf(SOLVE_APPLIED, PASS_USED, MEMBER_CHEAT_FAILED, GROUP_DECIDED, GROUP_RANKING_FINAL)

    private val json = JsonMapper.builder().build()
    private val PK = Regex("[0-9a-f]{64}")

    fun parse(fields: Map<String, String>): SignalRecord {
        val f = Fields(fields)
        return when (val type = f.req("type")) {
            SOLVE_APPLIED -> SolveRecord(
                date = f.date(),
                userId = f.uid(),
                questionNo = f.questionNo(),
                questionPk = f.pk(),
                successTime = f.iso("successTime"),
                clickedAt = f.micros("clickedAt"),
                arrivedAt = f.micros("arrivedAt"),
            )
            PASS_USED -> PassRecord(f.date(), f.uid(), f.questionNo(), f.pk(), f.micros("at"))
            MEMBER_CHEAT_FAILED -> CheatFailRecord(f.date(), f.uid(), f.micros("cheatFailedAt"))
            GROUP_DECIDED -> {
                val memberCount = f.int("memberCount")
                require(memberCount >= 1) { "memberCount 는 1 이상: $memberCount" }
                val raw = f.req("ranking")
                val members = ranking(raw)
                require(members.size == memberCount) { "ranking 줄 수 ${members.size} ≠ memberCount $memberCount" }
                GroupDecidedRecord(
                    date = f.date(),
                    groupId = GroupId(f.long("gid")),
                    result = enumValue("result", f.req("result")),
                    decidedAt = f.micros("decidedAt"),
                    memberCount = memberCount,
                    members = members,
                    rankingFinal = f.flag("rankingFinal"),
                    display = display(raw),
                )
            }
            GROUP_RANKING_FINAL -> {
                val raw = f.req("ranking")
                val members = ranking(raw)
                require(members.isNotEmpty()) { "ranking 이 비었다" }
                require(f.flag("rankingFinal")) { "group.ranking_final 의 rankingFinal 은 1" }
                GroupRankingFinalRecord(
                    date = f.date(),
                    groupId = GroupId(f.long("gid")),
                    result = enumValue("result", f.req("result")),
                    decidedAt = f.micros("decidedAt"),
                    members = members,
                    display = display(raw),
                )
            }
            else -> throw IllegalArgumentException("이 기능의 type 이 아님: $type")
        }
    }

    private fun ranking(raw: String): List<ResultMemberRecord> {
        val rows = try {
            json.readTree(raw)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("ranking JSON 을 읽지 못함", e)
        }
        require(rows.isArray) { "ranking 이 JSON 배열이 아님" }
        val members = ArrayList<ResultMemberRecord>()
        for (row in rows) {
            val progress = intOf(row, "p")
            require(progress in -1..3) { "ranking 줄의 p 범위 밖: $row" }
            val rank = intOf(row, "r")
            require(rank >= 1) { "ranking 줄의 r 은 1 이상: $row" }
            val pass = if (isAbsent(row, "n")) null else intOf(row, "n")
            require(pass == null || pass in 1..3) { "ranking 줄의 n 범위 밖: $row" }
            members += ResultMemberRecord(
                userId = UserId(idOf(row)),
                rank = rank,
                progress = progress,
                status = enumValue("ranking.s", textOf(row, "s")),
                lastSolvedAt = if (isAbsent(row, "l")) null else microsToInstant(long("ranking.l", textOf(row, "l"))),
                passNo = pass,
            )
        }
        require(members.map { it.userId }.toSet().size == members.size) { "ranking 에 같은 id 가 두 줄" }
        return members
    }

    /** 같은 랭킹 JSON 을 결과 캐시 모양으로 — 줄 형식 검사는 [ranking] 이 먼저 했다. 로그인 아이디(`u`) 등이 틀리면 null(캐시만 건너뛴다). */
    private fun display(raw: String): List<RankedMember>? =
        try {
            GroupResultState.rankingJson(raw)
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun isAbsent(row: JsonNode, name: String): Boolean = row.get(name)?.isNull ?: true

    private fun idOf(row: JsonNode): Long {
        val id = row.get("id")
        require(id != null && (id.isString || id.isIntegralNumber)) { "ranking 줄에 id 없음: $row" }
        return if (id.isIntegralNumber) id.longValue() else long("ranking.id", id.stringValue())
    }

    private fun intOf(row: JsonNode, name: String): Int {
        val v = row.get(name)
        require(v != null && v.isIntegralNumber) { "ranking 줄의 $name 이 정수가 아님: $row" }
        return v.intValue()
    }

    private fun textOf(row: JsonNode, name: String): String {
        val v = row.get(name)
        require(v != null && v.isString) { "ranking 줄의 $name 이 문자열이 아님: $row" }
        return v.stringValue()
    }

    private fun long(name: String, value: String): Long =
        requireNotNull(value.toLongOrNull()) { "$name 은 정수: $value" }

    private inline fun <reified E : Enum<E>> enumValue(name: String, value: String): E =
        requireNotNull(enumValues<E>().firstOrNull { it.name == value }) { "$name 값 틀림: $value" }

    private class Fields(private val fields: Map<String, String>) {
        fun req(name: String): String = requireNotNull(fields[name]) { "$name 칸 없음" }

        fun long(name: String): Long = long(name, req(name))

        fun int(name: String): Int = requireNotNull(req(name).toIntOrNull()) { "$name 은 정수: ${fields[name]}" }

        fun micros(name: String): Instant = microsToInstant(long(name))

        fun date(): LocalDate = try {
            LocalDate.parse(req("date"))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("date 는 YYYY-MM-DD: ${fields["date"]}", e)
        }

        fun flag(name: String): Boolean = when (req(name)) {
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException("$name 은 0 · 1: ${fields[name]}")
        }

        fun uid(): UserId = UserId(long("uid"))

        fun questionNo(): Int = int("questionNo").also { require(it in 1..3) { "questionNo 는 1~3: $it" } }

        fun pk(): String = req("questionId").also { require(PK.matches(it)) { "questionId 는 hex 64: $it" } }

        fun iso(name: String): Instant = try {
            OffsetDateTime.parse(req(name)).toInstant()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("$name 은 오프셋 있는 ISO-8601: ${fields[name]}", e)
        }
    }
}
