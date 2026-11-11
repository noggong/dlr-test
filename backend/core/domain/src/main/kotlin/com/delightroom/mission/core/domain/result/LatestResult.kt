package com.delightroom.mission.core.domain.result

import com.delightroom.mission.core.domain.board.GroupResultCode
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.user.LoginId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/** 결과 랭킹 한 줄(LLD deadline-result §1 · SPEC MemberEntry). [progress] 는 내부 값 -1~3 — API 는 -1 을 `0` · `started false` 로 낸다. */
data class RankedMember(
    val loginId: LoginId,
    val rank: Int,
    val progress: Int,
    val status: MemberStatus,
    val lastSolvedAt: Instant?,
    val passNo: Int?,
) {
    init {
        require(rank >= 1) { "rank 는 1 이상: $rank" }
        require(progress in -1..3) { "progress 는 -1~3: $progress" }
        require(passNo == null || passNo in 1..3) { "passNo 는 1~3: $passNo" }
    }
}

/**
 * 결과 조회의 도메인 값(LLD deadline-result §3.3) — 그룹 Hash · 결과 캐시 · PostgreSQL 어느 쪽에서 읽어도 같은 모양이다.
 * [ranking] 은 rank 오름차순, 같으면 로그인 아이디 바이트 순(DR7). `myRank` · `nextGameStartAt` 은 요청마다 붙이므로 넣지 않는다.
 */
data class LatestResult(
    val date: LocalDate,
    val groupStatus: GroupStatus,
    val decidedAt: Instant?,
    val rankingFinal: Boolean,
    val ranking: List<RankedMember>,
) {
    /** [loginId] 의 순위 — 그날 그룹원이 아니었으면 null(DR9). */
    fun rankOf(loginId: LoginId): Int? = ranking.firstOrNull { it.loginId == loginId }?.rank

    companion object {
        /** 08:00 뒤 결과 확정 대기(DR2) — `decidedAt null` · `rankingFinal false` · `ranking []`. */
        fun pending(date: LocalDate): LatestResult = LatestResult(date, GroupStatus.PENDING, null, false, emptyList())

        /** 표시 순서(DR7)로 정렬한다. */
        fun sorted(ranking: List<RankedMember>): List<RankedMember> =
            ranking.sortedWith(compareBy<RankedMember> { it.rank }.thenBy { it.loginId.value })
    }
}

/** 그룹 Hash `result` → API 그룹 상태(LLD deadline-result §2.3) — `FAILED_*` 는 `FAILED`. */
fun GroupResultCode.toGroupStatus(): GroupStatus =
    when (this) {
        GroupResultCode.SUCCEEDED -> GroupStatus.SUCCEEDED
        GroupResultCode.FAILED_IMMEDIATE, GroupResultCode.FAILED_DEADLINE -> GroupStatus.FAILED
    }

/**
 * 오늘 그룹 Hash 의 결과 칸(`HMGET version result decidedAt ranking rankingFinal` — LLD deadline-result §3.3 1 · 2).
 * Hash 가 없으면(`version` 없음) 이 값 대신 null 이다.
 */
data class GroupResultState(
    val result: GroupResultCode?,
    val decidedAt: Instant?,
    val rankingFinal: Boolean,
    val ranking: List<RankedMember>,
) {
    /** 결과가 있으면 그 결과(§3.3 2), 없으면 null. */
    fun toLatest(date: LocalDate): LatestResult? =
        result?.let { LatestResult(date, it.toGroupStatus(), decidedAt, rankingFinal, ranking) }

    companion object {
        private val json: JsonMapper = JsonMapper.builder().build()

        /**
         * `HMGET` 다섯 칸을 읽는다 — `version` 이 없으면 null(오늘 Hash 없음). `result` 가 있는데 `decidedAt` · `ranking` 이
         * 없거나 값이 틀리면 [IllegalArgumentException](Lua `deadline` · `solve` 는 셋을 함께 쓴다).
         */
        fun parse(version: String?, result: String?, decidedAt: String?, ranking: String?, rankingFinal: String?): GroupResultState? {
            if (version == null) return null
            val code = result?.let { r ->
                requireNotNull(GroupResultCode.entries.firstOrNull { it.name == r }) { "result 값 틀림: $r" }
            }
            require(code == null || (decidedAt != null && ranking != null)) { "result 가 있는데 decidedAt · ranking 이 없다" }
            require(rankingFinal == null || rankingFinal == "0" || rankingFinal == "1") { "rankingFinal 은 0 · 1 · 없음: $rankingFinal" }
            return GroupResultState(
                result = code,
                decidedAt = decidedAt?.let { microsToInstant(requireNotNull(it.toLongOrNull()) { "decidedAt 은 정수: $it" }) },
                rankingFinal = rankingFinal == "1",
                ranking = if (code == null || ranking == null) emptyList() else rankingJson(ranking),
            )
        }

        /**
         * 랭킹 JSON(LLD deadline-result §2.2) — 줄 `{"id","u","r","p","s","l","n"}`. 키 순서는 정해지지 않으므로(cjson) 파싱해서 읽고,
         * `l` 은 µs 십진 문자열 · null 이다. 표시 순서로 다시 정렬해 돌려준다.
         */
        fun rankingJson(raw: String): List<RankedMember> {
            val rows = try {
                json.readTree(raw)
            } catch (e: JacksonException) {
                throw IllegalArgumentException("ranking JSON 을 읽지 못함", e)
            }
            require(rows.isArray) { "ranking 이 JSON 배열이 아님" }
            return LatestResult.sorted(rows.toList().map(::row))
        }

        private fun row(row: JsonNode): RankedMember {
            fun int(name: String): Int? = row.get(name)?.takeUnless { it.isNull }?.let { n ->
                require(n.isIntegralNumber) { "ranking 줄의 $name 이 정수가 아님: $row" }
                n.intValue()
            }
            val login = row.get("u")
            require(login != null && login.isString) { "ranking 줄에 u 없음: $row" }
            val status = row.get("s")
            require(status != null && status.isString) { "ranking 줄에 s 없음: $row" }
            val last = row.get("l")?.takeUnless { it.isNull }?.let { l ->
                require(l.isString) { "ranking 줄의 l 은 µs 문자열: $row" }
                microsToInstant(requireNotNull(l.stringValue().toLongOrNull()) { "ranking 줄의 l 은 µs 정수: $row" })
            }
            return RankedMember(
                loginId = LoginId(login.stringValue()),
                rank = requireNotNull(int("r")) { "ranking 줄에 r 없음: $row" },
                progress = requireNotNull(int("p")) { "ranking 줄에 p 없음: $row" },
                status = requireNotNull(MemberStatus.entries.firstOrNull { it.name == status.stringValue() }) { "ranking 줄의 s 틀림: $row" },
                lastSolvedAt = last,
                passNo = int("n"),
            )
        }
    }
}
