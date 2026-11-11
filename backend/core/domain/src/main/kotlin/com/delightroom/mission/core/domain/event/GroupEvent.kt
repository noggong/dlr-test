package com.delightroom.mission.core.domain.event

import com.delightroom.mission.common.error.DependencyUnavailableException
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import java.time.Instant
import java.time.LocalDate

/** 그룹 채널 메시지 `type`(LLD realtime-board §2.3 · HLD platform §4.2). [wire] 가 채널 JSON 의 문자열이다. */
enum class GroupChannelType(val wire: String) {
    MISSION_STARTED("mission.started"),
    SOLVE_APPLIED("solve.applied"),
    PASS_USED("pass.used"),
    MEMBER_CHEAT_FAILED("member.cheat_failed"),
    GROUP_DECIDED("group.decided"),
    GROUP_PENDING("group.pending"),
    GROUP_RANKING_FINAL("group.ranking_final"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun ofWire(wire: String): GroupChannelType? = byWire[wire]
    }
}

/**
 * 그룹 채널 메시지 한 개(`SPUBLISH ch:{<b>}:<gid>` 본문 — LLD realtime-board §2.3). 그룹을 바꾼 Lua 한 번에 한 개이고,
 * 반영 뒤 그룹 Hash 전체를 [snapshot] 으로 싣는다 — 받는 쪽은 앞 메시지 없이 이것만으로 현황판을 만든다.
 */
data class GroupChannelMessage(
    val type: GroupChannelType,
    val groupId: GroupId,
    val date: LocalDate,
    /** 마지막 `XADD` id(로그 상관용). Stream 에 남기지 않는 `group.pending` 은 null. */
    val streamId: String?,
    /** 바뀐 그룹원. 그룹 단위 일이면 null. */
    val changedUserId: UserId?,
    /** 반영 뒤 그룹 Hash `version`. */
    val version: Long,
    /** 반영한 Redis 시각. */
    val at: Instant,
    val snapshot: GroupDaySnapshot,
)

/** 그룹 채널 구독 연결이 끊겼다 — 그 구독으로 받던 스트림은 모두 닫는다(LLD realtime-board §5). 응답 전이면 503 이다(prd D21). */
class GroupChannelLostException(message: String, cause: Throwable? = null) : DependencyUnavailableException(message, cause)
