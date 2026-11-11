package com.delightroom.mission.core.domain.board

import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/** 그룹 Hash `result` 값(LLD deadline-result §2.2). `FAILED_*` 는 그룹 상태 `FAILED` 로 보인다. */
enum class GroupResultCode {
    SUCCEEDED,
    FAILED_IMMEDIATE,
    FAILED_DEADLINE,
}

/** 그룹 Hash 의 그룹원 한 명(`<uid>:*` 필드 — LLD realtime-board §2.2). 보는 사람별 규칙을 적용하기 전 원래 값이다. */
data class MemberState(
    val userId: UserId,
    val loginId: LoginId,
    val status: MemberStatus,
    /** -1(미접속) ~ 3. */
    val progress: Int,
    /** 지각(D8). 필드가 없으면 false. */
    val late: Boolean,
    /** 마지막 풀이 도착(에포크 µs). */
    val lastSolvedAtUs: Long?,
    val passNo: Int?,
)

/**
 * 그날 그룹 Hash `g:{<b>}:<gid>:<date>` 를 읽은 값(LLD realtime-board §2.2). 그룹원 목록은 `<uid>:lid` 필드로 정한다.
 * 조회는 Lua `board_read` 의 `HGETALL` 평탄 목록([parseFlat]), 스트림은 채널 메시지의 `hash` 객체([parse])에서 만든다.
 */
data class GroupDaySnapshot(
    val date: LocalDate,
    val version: Long,
    /** `pending = 1` — 08:00 마감 1차에서 미룬 그룹. */
    val pending: Boolean,
    val result: GroupResultCode?,
    val decidedAt: Instant?,
    val rankingFinal: Boolean,
    /** Hash `ranking` 의 uid → 순위. 결과가 없으면 비어 있다. */
    val ranking: Map<UserId, Int>,
    val members: Map<UserId, MemberState>,
) {
    companion object {
        private val MEMBER_FIELD = Regex("""(\d+):lid""")
        private val json: JsonMapper = JsonMapper.builder().build()

        /** `HGETALL` 응답(필드, 값, 필드, 값, …). 모양이 틀리면 [IllegalArgumentException]. */
        fun parseFlat(date: LocalDate, flat: List<String>): GroupDaySnapshot {
            require(flat.size % 2 == 0) { "HGETALL 목록 길이가 홀수: ${flat.size}" }
            return parse(date, flat.chunked(2).associate { (field, value) -> field to value })
        }

        /** 필드 → 값. 필수 필드(`version` · 그룹원의 `st` · `p`)가 없거나 값이 틀리면 [IllegalArgumentException]. */
        fun parse(date: LocalDate, fields: Map<String, String>): GroupDaySnapshot {
            val members = fields.keys.mapNotNull { MEMBER_FIELD.matchEntire(it)?.groupValues?.get(1) }
                .map { uid -> member(uid, fields) }
                .associateBy { it.userId }
            return GroupDaySnapshot(
                date = date,
                version = requireNotNull(fields["version"]?.toLongOrNull()) { "version 없음 · 틀림: ${fields["version"]}" },
                pending = fields["pending"] == "1",
                result = fields["result"]?.let { enumValue<GroupResultCode>("result", it) },
                decidedAt = fields["decidedAt"]?.let { microsToInstant(long("decidedAt", it)) },
                rankingFinal = fields["rankingFinal"] == "1",
                ranking = fields["ranking"]?.let(::ranking) ?: emptyMap(),
                members = members,
            )
        }

        private fun member(uid: String, fields: Map<String, String>): MemberState {
            fun required(name: String) = requireNotNull(fields["$uid:$name"]) { "$uid:$name 없음" }
            val progress = required("p").toIntOrNull()
            require(progress != null && progress in -1..3) { "$uid:p 는 -1~3: ${fields["$uid:p"]}" }
            val passNo = fields["$uid:pn"]?.toIntOrNull()
            require(fields["$uid:pn"] == null || passNo in 1..3) { "$uid:pn 는 1~3: ${fields["$uid:pn"]}" }
            return MemberState(
                userId = UserId(uid.toLong()),
                loginId = LoginId(required("lid")),
                status = enumValue<MemberStatus>("$uid:st", required("st")),
                progress = progress,
                late = fields["$uid:late"] == "1",
                lastSolvedAtUs = fields["$uid:ls"]?.let { long("$uid:ls", it) },
                passNo = passNo,
            )
        }

        /** 랭킹 JSON 배열(LLD deadline-result §2.2) — 줄마다 `id`(uid) 와 `r`(순위)만 읽는다. */
        private fun ranking(raw: String): Map<UserId, Int> {
            val rows = try {
                json.readTree(raw)
            } catch (e: JacksonException) {
                throw IllegalArgumentException("ranking JSON 을 읽지 못함", e)
            }
            require(rows.isArray) { "ranking 이 JSON 배열이 아님" }
            return rows.associate { row -> UserId(idOf(row)) to rankOf(row) }
        }

        private fun idOf(row: JsonNode): Long {
            val id = row.get("id")
            require(id != null && (id.isString || id.isIntegralNumber)) { "ranking 줄에 id 없음: $row" }
            return if (id.isIntegralNumber) id.longValue() else long("ranking.id", id.stringValue())
        }

        private fun rankOf(row: JsonNode): Int {
            val rank = row.get("r")
            require(rank != null && rank.isIntegralNumber && rank.intValue() >= 1) { "ranking 줄의 r 틀림: $row" }
            return rank.intValue()
        }

        private fun long(name: String, value: String): Long =
            requireNotNull(value.toLongOrNull()) { "$name 은 정수: $value" }

        private inline fun <reified E : Enum<E>> enumValue(name: String, value: String): E =
            requireNotNull(enumValues<E>().firstOrNull { it.name == value }) { "$name 값 틀림: $value" }
    }
}

/** 에포크 µs → [Instant](µs 정밀도 그대로). 음수도 내림으로 나눈다. */
fun microsToInstant(us: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(us, 1_000_000L), Math.floorMod(us, 1_000_000L) * 1_000L)
