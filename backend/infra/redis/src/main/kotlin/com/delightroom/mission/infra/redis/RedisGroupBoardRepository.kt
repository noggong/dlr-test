package com.delightroom.mission.infra.redis

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.GroupBoardRepository
import java.time.Instant
import java.time.LocalDate

/** [GroupBoardRepository] 의 Redis 구현 — 판정 · 읽기는 Lua `board_read`(LLD realtime-board §3) 한 번이다. */
internal class RedisGroupBoardRepository(
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
    private val properties: GameProperties,
) : GroupBoardRepository {
    override suspend fun read(groupId: GroupId, date: LocalDate, userId: UserId, mode: BoardReadMode): BoardRead {
        val (from, to) = window(date, mode)
        val reply = scripts.eval(
            "board_read",
            listOf(keys.members(groupId, date), keys.group(groupId, date)),
            listOf(
                userId.value.toString(),
                from.toString(),
                to.toString(),
                properties.clockOffsetUs.toString(),
                mode.name.lowercase(),
                properties.minGroupSize.toString(),
            ),
        )
        return when (reply.firstOrNull()) {
            "ERR" -> {
                check(reply.size == 2) { "board_read 거부 반환은 {ERR, code} 다: $reply" }
                val code = REJECTIONS.firstOrNull { it.name == reply[1] }
                checkNotNull(code) { "board_read 가 모르는 거부 코드를 돌려줬다: $reply" }
                BoardRead.Rejected(code)
            }
            "OK" -> {
                check(reply.size >= 2 && reply.size % 2 == 0) { "board_read 통과 반환은 {OK, now, 필드, 값…} 이다: $reply" }
                BoardRead.Opened(microsToInstant((reply[1] as String).toLong()), reply.drop(2).map { it as String })
            }
            else -> error("board_read 반환의 첫 칸은 OK · ERR 이다: $reply")
        }
    }

    /** 모드별 창 [시작, 끝) µs — LLD §3 표. */
    private fun window(date: LocalDate, mode: BoardReadMode): Pair<Long, Long> {
        val start = date.atTime(properties.startTime).atZone(properties.zone).toInstant()
        return when (mode) {
            BoardReadMode.BOARD -> micros(start) to micros(properties.deadlineAt(date))
            BoardReadMode.TICKET -> micros(start) to micros(properties.graceEndAt(date))
            BoardReadMode.STREAM -> 0L to Long.MAX_VALUE
        }
    }

    private fun micros(at: Instant): Long = Math.addExact(Math.multiplyExact(at.epochSecond, 1_000_000L), at.nano / 1_000L)

    private companion object {
        val REJECTIONS = setOf(ErrorCode.GAME_NOT_OPEN, ErrorCode.NOT_PARTICIPANT, ErrorCode.SOLO_GROUP)
    }
}
