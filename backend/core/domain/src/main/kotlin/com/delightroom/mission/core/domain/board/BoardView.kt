package com.delightroom.mission.core.domain.board

import com.delightroom.mission.core.domain.ranking.Ranking
import com.delightroom.mission.core.domain.ranking.RankingKey
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant

/**
 * 보는 사람별 현황판 만들기(HLD realtime-board §3.1 · LLD §4.5 · SPEC RB10 · RB11). 조회와 스트림이 같은 함수를 쓴다(RB12).
 * 순수 함수 — 시각은 인자로만 받는다.
 */
object BoardView {
    /**
     * @param now 판정 시각 — 조회 · snapshot 은 Lua `now`, 스트림은 채널 메시지 `at`.
     * @param deadlineAt 그날 08:00:00 — 결과 없이 이 시각을 지나면 그룹 상태 `PENDING`.
     */
    fun build(snapshot: GroupDaySnapshot, viewer: UserId, now: Instant, deadlineAt: Instant): Board {
        val decided = snapshot.result != null
        val ranks = ranks(snapshot)
        val entries = snapshot.members.values.map { m ->
            BoardEntry(
                userId = m.loginId,
                rank = ranks.getValue(m.userId),
                progress = maxOf(m.progress, 0),
                status = visibleStatus(m, viewer, decided),
                started = m.progress >= 0,
                lastSolvedAt = m.lastSolvedAtUs?.let(::microsToInstant),
                passedAtQuestionNo = m.passNo,
            )
        }.sortedWith(compareBy<BoardEntry> { it.rank }.thenBy { it.userId.value })
        return Board(snapshot.date, groupStatus(snapshot, now, deadlineAt), snapshot.version, entries)
    }

    private fun groupStatus(snapshot: GroupDaySnapshot, now: Instant, deadlineAt: Instant): GroupStatus =
        when (snapshot.result) {
            GroupResultCode.SUCCEEDED -> GroupStatus.SUCCEEDED
            GroupResultCode.FAILED_IMMEDIATE, GroupResultCode.FAILED_DEADLINE -> GroupStatus.FAILED
            null -> if (snapshot.pending || !now.isBefore(deadlineAt)) GroupStatus.PENDING else GroupStatus.IN_PROGRESS
        }

    /** 결과 확정 전 남의 부정 실패는 진행 중으로 보인다(D2). 순위는 원래 상태로 계산하므로 여전히 맨 아래다. */
    private fun visibleStatus(member: MemberState, viewer: UserId, decided: Boolean): MemberStatus =
        if (!decided && member.status == MemberStatus.CHEAT_FAILED && member.userId != viewer) {
            MemberStatus.IN_PROGRESS
        } else {
            member.status
        }

    /**
     * 결과가 있으면 Hash `ranking`(결과 조회 · PostgreSQL 과 같은 값 — NFR-4). 결과가 없거나 `ranking` 이 그룹원 전원을
     * 담지 않은 비정상 Hash 면 원래 상태로 `Ranking.rank` 를 계산한다 — 섞지 않고 전원을 한쪽 값으로.
     */
    private fun ranks(snapshot: GroupDaySnapshot): Map<UserId, Int> {
        if (snapshot.result != null && snapshot.ranking.keys.containsAll(snapshot.members.keys)) return snapshot.ranking
        val byLogin = snapshot.members.values.associateBy { it.loginId }
        return Ranking.rank(snapshot.members.values.map(::rankingKey))
            .associate { byLogin.getValue(it.key.loginId).userId to it.rank }
    }

    private fun rankingKey(m: MemberState): RankingKey =
        RankingKey(
            loginId = m.loginId,
            cheatFailed = m.status == MemberStatus.CHEAT_FAILED,
            progress = m.progress,
            lastSolvedAtUs = m.lastSolvedAtUs,
            passNo = m.passNo,
            late = m.late,
        )
}
