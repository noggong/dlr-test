package com.delightroom.mission.core.domain.event

import com.delightroom.mission.core.domain.board.GroupResult
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.LoginId
import java.time.Instant

/** 그룹원에게 실시간으로 흘려보내는 사건(prd §5.8). */
sealed interface GroupEvent {
    val groupId: GroupId
    val occurredAt: Instant

    /** 그룹원 진행도 변경(R-R2). */
    data class MemberProgressed(
        override val groupId: GroupId,
        override val occurredAt: Instant,
        val loginId: LoginId,
        val displayProgress: Int,
        val status: MemberStatus,
    ) : GroupEvent

    /** 그룹 결과 확정 — 성공 · 즉시 실패 · 마감 실패(R-R3). */
    data class ResultDecided(
        override val groupId: GroupId,
        override val occurredAt: Instant,
        val result: GroupResult,
    ) : GroupEvent
}
