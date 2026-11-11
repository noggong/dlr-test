package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlineOutcome
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.port.GroupDayRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import java.time.Instant
import java.time.LocalDate

/** [GroupDayRepository] 의 Redis 구현 — 마감 판정은 Lua `deadline`(LLD deadline-result §4.1)이 원자적으로 한다. */
internal class RedisGroupDayRepository(
    private val redis: ReactiveStringRedisTemplate,
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

    override fun scanDayGroups(bucket: Int, date: LocalDate): Flow<GroupId> =
        redis.opsForSet().scan(keys.dayGroups(bucket, date), SCAN).asFlow().mapNotNull { gid ->
            // 데이터 오류 하나로 버킷 전체를 멈추지 않는다(LLD §3.2 5) — 건너뛰고 남긴다
            gid.toLongOrNull()?.let(::GroupId)
                ?: null.also { log.warn("deadline.bad_gid bucket={} date={} value={}", bucket, date, gid) }
        }

    private fun micros(at: Instant): Long = Math.addExact(Math.multiplyExact(at.epochSecond, 1_000_000L), at.nano / 1_000L)

    private companion object {
        val log = LoggerFactory.getLogger(RedisGroupDayRepository::class.java)

        /** LLD deadline-result §3.2 4 — `SSCAN … COUNT 1000`. */
        val SCAN: ScanOptions = ScanOptions.scanOptions().count(1_000).build()
    }
}
