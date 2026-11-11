package com.delightroom.mission.core.domain.roster

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/**
 * 그룹원 한 명의 그날 PostgreSQL 기록(LLD platform §4.2 R1 한 줄) — `daily_progress` 에 `solve_signals` 의 가장 큰 번호 ·
 * 패스로 넘긴 문제 PK · 그날 「지금 문제」(SHOWN · 번호 = 진행도 + 1) PK 를 붙인 것.
 */
data class MemberDayRecord(
    val userId: UserId,
    val progress: Int,
    val status: String,
    val startedAt: Instant? = null,
    val late: Boolean? = null,
    val lastSolvedAt: Instant? = null,
    val passNo: Int? = null,
    val passedPk: String? = null,
    val cheatFailedAt: Instant? = null,
    /** `solve_signals` 의 가장 큰 `question_no` 와 그 행의 `arrived_at` — 없으면 null. */
    val solvedNo: Int? = null,
    val solvedAt: Instant? = null,
    val currentPk: String? = null,
)

/** 그룹 하나의 그날 PostgreSQL 기록(R1 · R2). [result] · [decidedAt] 은 `group_results` 행이 없으면 null. */
data class GroupDayRecord(
    val members: List<MemberDayRecord>,
    val result: String? = null,
    val decidedAt: Instant? = null,
)

/**
 * 재적재로 그룹 Hash 에 덮을 칸(LLD platform §4.2 칸 매핑) — `roster.lua` 꼬리로 넘긴다.
 * [fields] 는 기본 칸(-1 · `NOT_STARTED`) 위에 덮을 칸, [version] 은 그룹 Hash `version`, [rerank] 면 Lua 가 `ranking` 을 다시 계산한다.
 */
class GroupRestore(
    val version: Long,
    val fields: Map<String, String>,
    val rerank: Boolean,
) {
    override fun toString(): String = "GroupRestore(version=$version, rerank=$rerank, fields=$fields)"

    companion object {
        private val STATUSES = setOf("NOT_STARTED", "IN_PROGRESS", "SUCCEEDED", "CHEAT_FAILED", "FAILED")
        private val RESULTS = setOf("SUCCEEDED", "FAILED_IMMEDIATE", "FAILED_DEADLINE")

        /**
         * [roster] 와 그날 기록 [record] 로 덮을 칸을 만든다. 기록이 없으면(행 0 · 결과 없음) null — 처음 만드는 명단이다(재적재 판정).
         * [now] 는 앱 게임 시각 — `version` = 그 epoch ms(옛 `version` 보다 크게 · cjson 14자리 안).
         * [deadlineAt] 은 그날 08:00 — 그 전에 난 결과(조기 확정)는 랭킹이 곧바로 확정이다(Lua `decide.run` 과 같다).
         * 명단에 없는 그룹원의 행은 버린다. 값이 규칙 밖이면(상태 · 진행도 · 결과) [IllegalArgumentException] — 조용히 올리지 않는다.
         */
        fun of(roster: DayRoster, record: GroupDayRecord?, now: Instant, deadlineAt: Instant): GroupRestore? {
            if (record == null || (record.members.isEmpty() && record.result == null)) return null
            val fields = LinkedHashMap<String, String>()
            record.members.filter { roster.contains(it.userId) }.sortedBy { it.userId.value }.forEach { m ->
                require(m.status in STATUSES) { "daily_progress.status 가 규칙 밖이다: ${m.userId.value} ${m.status}" }
                require(m.progress in -1..3) { "daily_progress.progress 가 규칙 밖이다: ${m.userId.value} ${m.progress}" }
                val u = m.userId.value
                // 진행도는 daily_progress 와 solve_signals 의 큰 쪽 — 같은 트랜잭션에서 쓰므로 보통 같다
                val bySignals = m.solvedNo != null && m.solvedNo > m.progress
                val p = if (bySignals) m.solvedNo!! else m.progress
                require(p in -1..3) { "solve_signals.question_no 가 규칙 밖이다: $u $p" }
                val st = if (bySignals && p == 3 && m.status in setOf("NOT_STARTED", "IN_PROGRESS")) "SUCCEEDED" else m.status
                val ls = if (bySignals) m.solvedAt else m.lastSolvedAt
                // 랭킹 다시 계산(ranking.fromHash)은 진행도 ≥ 1 이면 마지막 풀이 시각을 요구한다
                require(p < 1 || ls != null) { "진행도 $p 인데 마지막 풀이 시각이 없다: $u" }
                fields["$u:p"] = p.toString()
                fields["$u:st"] = st
                m.startedAt?.let { fields["$u:sa"] = us(it) }
                m.late?.let { fields["$u:late"] = if (it) "1" else "0" }
                ls?.let { fields["$u:ls"] = us(it) }
                if (m.passNo != null && m.passedPk != null) {
                    fields["$u:pn"] = m.passNo.toString()
                    fields["$u:pq"] = m.passedPk
                }
                if (p < 3) m.currentPk?.let { fields["$u:cur"] = it }
                m.cheatFailedAt?.let { fields["$u:cf"] = us(it) }
            }
            val result = record.result
            if (result != null) {
                require(result in RESULTS) { "group_results.result 가 규칙 밖이다: $result" }
                fields["result"] = result
                val decidedAt = requireNotNull(record.decidedAt) { "group_results.completed_at 이 없다" }
                fields["decidedAt"] = us(decidedAt)
                // 08:00 전 확정은 그 자리에서 확정 랭킹, 08:00 뒤(마감) 확정은 08:00:03 의 랭킹 확정을 마감 따라잡기 · 마감 잡이 다시 낸다
                fields["rankingFinal"] = if (decidedAt < deadlineAt) "1" else "0"
            }
            return GroupRestore(now.toEpochMilli(), fields, rerank = result != null)
        }

        private fun us(instant: Instant): String = GameClock.epochMicros(instant).toString()
    }
}
