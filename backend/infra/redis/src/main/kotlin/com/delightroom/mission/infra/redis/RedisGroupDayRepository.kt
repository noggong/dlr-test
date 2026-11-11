package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlineOutcome
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.port.GroupDayRepository
import java.time.Instant
import java.time.LocalDate

/** [GroupDayRepository] 의 Redis 구현 — 마감 판정은 Lua `deadline`(LLD deadline-result §4.1)이 원자적으로 한다. */
internal class RedisGroupDayRepository(
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
    private val properties: GameProperties,
) : GroupDayRepository {
    override suspend fun runDeadline(gid: GroupId, date: LocalDate, phase: DeadlinePhase): DeadlineOutcome {
        val reply = scripts.eval(
            "deadline",
            listOf(keys.group(gid, date), keys.members(gid, date), keys.events(gid)),
            listOf(
                phase.value.toString(),
                date.toString(),
                gid.value.toString(),
                micros(properties.deadlineAt(date)).toString(),
                micros(properties.graceEndAt(date)).toString(),
                keys.channel(gid),
                properties.clockOffsetUs.toString(),
            ),
        )
        check(reply.size == 2) { "deadline 반환은 {code, version} 이다: $reply" }
        return DeadlineOutcome(DeadlineCode.valueOf(reply[0] as String), reply[1] as Long)
    }

    private fun micros(at: Instant): Long = Math.addExact(Math.multiplyExact(at.epochSecond, 1_000_000L), at.nano / 1_000L)
}
