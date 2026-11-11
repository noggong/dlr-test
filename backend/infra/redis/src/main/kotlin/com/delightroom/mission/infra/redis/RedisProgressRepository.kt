package com.delightroom.mission.infra.redis

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.ProgressRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.LocalDate

internal class RedisProgressRepository(
    private val redis: ReactiveStringRedisTemplate,
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
    private val gameClock: GameClock,
    private val properties: GameProperties,
) : ProgressRepository {
    override suspend fun find(userId: UserId, date: LocalDate): MissionProgress? = TODO("solve-signal")

    override suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress> = TODO("realtime-board")

    override suspend fun save(progress: MissionProgress): MissionProgress = TODO("solve-signal")

    /** `start.lua`(LLD game-session §4.2) — 경계 시각은 앱이 [date] 로 고르고 「지금」은 Lua 가 Redis `TIME` + 오프셋으로 본다. */
    override suspend fun startMission(userId: UserId, groupId: GroupId, date: LocalDate): MissionStartOutcome {
        val reply = scripts.eval(
            "start",
            listOf(keys.group(groupId, date), keys.members(groupId, date), keys.events(groupId)),
            listOf(
                userId.value.toString(),
                gameClock.startUs(date).toString(),
                gameClock.lateUs(date).toString(),
                gameClock.deadlineUs(date).toString(),
                properties.clockOffsetUs.toString(),
                keys.channel(groupId),
                date.toString(),
                groupId.value.toString(),
            ),
        )
        return when (reply.firstOrNull()) {
            "OK" -> {
                check(reply.size == 5) { "start 통과 반환은 {OK, sa, created, p, st} 다: $reply" }
                MissionStartOutcome.Started(microsToInstant((reply[1] as String).toLong()), reply[2] == "1")
            }
            "NO_ROSTER" -> MissionStartOutcome.NoRoster
            "ERR" -> {
                check(reply.size == 2) { "start 거부 반환은 {ERR, code} 다: $reply" }
                val code = START_REJECTIONS.firstOrNull { it.name == reply[1] }
                checkNotNull(code) { "start 가 모르는 거부 코드를 돌려줬다: $reply" }
                MissionStartOutcome.Rejected(code)
            }
            else -> error("start 반환의 첫 칸은 OK · NO_ROSTER · ERR 이다: $reply")
        }
    }

    private companion object {
        /** `start.lua` 가 내는 거부 코드(SPEC GS14). */
        val START_REJECTIONS = listOf(
            ErrorCode.GAME_NOT_OPEN,
            ErrorCode.SUBMISSION_CLOSED,
            ErrorCode.NOT_PARTICIPANT,
            ErrorCode.SOLO_GROUP,
        )
    }
}
