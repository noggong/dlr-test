package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.FailCommand
import com.delightroom.mission.core.domain.progress.MissionProgress
import com.delightroom.mission.core.domain.progress.PassCommand
import com.delightroom.mission.core.domain.progress.SignalOutcome
import com.delightroom.mission.core.domain.progress.SignalRejection
import com.delightroom.mission.core.domain.progress.SignalReply
import com.delightroom.mission.core.domain.progress.SignalResult
import com.delightroom.mission.core.domain.progress.SignalTarget
import com.delightroom.mission.core.domain.progress.SolveCommand
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.ProgressRepository
import java.time.LocalDate

/**
 * 신호 Lua `solve` · `pass` · `fail` 을 부르고 반환 배열을 해석한다(LLD solve-signal §3.1 ~ §3.3 · §3.5).
 * `EVALSHA` 는 한 번만 — `NOSCRIPT` 재적재 1회는 [RedisScripts] 가 한다.
 */
internal class RedisProgressRepository(
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
    private val properties: GameProperties,
) : ProgressRepository {
    override suspend fun find(userId: UserId, date: LocalDate): MissionProgress? = TODO("solve-signal")

    override suspend fun findGroup(groupId: GroupId, date: LocalDate): List<MissionProgress> = TODO("realtime-board")

    override suspend fun save(progress: MissionProgress): MissionProgress = TODO("solve-signal")

    override suspend fun applySolve(command: SolveCommand): SignalReply {
        val s = command.signal
        val args = window(command.target) + listOf(
            s.questionNo.toString(),
            s.questionId.value,
            GameClock.epochMicros(s.clickedAt).toString(),
            s.successTime,
        )
        return parse("solve", run("solve", command.target, args), size = 6)
    }

    override suspend fun applyPass(command: PassCommand): SignalReply {
        val s = command.signal
        val args = window(command.target) + listOf(s.questionNo.toString(), s.questionId.value)
        return parse("pass", run("pass", command.target, args), size = 7)
    }

    override suspend fun applyCheatFail(command: FailCommand): SignalReply =
        parse("fail", run("fail", command.target, window(command.target)), size = 6)

    private suspend fun run(name: String, target: SignalTarget, args: List<String>): List<Any> =
        scripts.eval(
            name,
            listOf(
                keys.group(target.groupId, target.date),
                keys.members(target.groupId, target.date),
                keys.events(target.groupId),
                keys.channel(target.groupId),
            ),
            args,
        )

    /** ARGV[1] ~ ARGV[7] — uid · gid · date · openAt · deadlineAt · graceEndAt · offsetUs. */
    private fun window(target: SignalTarget): List<String> = listOf(
        target.userId.value.toString(),
        target.groupId.value.toString(),
        target.date.toString(),
        target.openAtUs.toString(),
        target.deadlineAtUs.toString(),
        target.graceEndAtUs.toString(),
        properties.clockOffsetUs.toString(),
    )

    /** `[코드, p, st, groupStatus, version, t]`(+ pass 는 `[7] pn`, 없으면 0). 거부 코드면 [3] · [4] 가 빈 문자열일 수 있다. */
    private fun parse(name: String, reply: List<Any>, size: Int): SignalReply {
        check(reply.size == size) { "$name 반환은 ${size}칸이다: $reply" }
        val code = reply[0] as String
        val arrivedAt = microsToInstant(reply[5] as Long)
        val outcome = when (code) {
            OK -> SignalOutcome.APPLIED
            REPLAY -> SignalOutcome.REPLAY
            else -> return SignalRejection(code, arrivedAt)
        }
        val passNo = if (size == 7) (reply[6] as Long).toInt() else 0
        return SignalResult(
            outcome = outcome,
            progress = (reply[1] as Long).toInt(),
            missionStatus = MemberStatus.valueOf(reply[2] as String),
            groupStatus = GroupStatus.valueOf(reply[3] as String),
            version = reply[4] as Long,
            arrivedAt = arrivedAt,
            passedAtQuestionNo = passNo.takeIf { it > 0 },
        )
    }

    private companion object {
        const val OK = "OK"
        const val REPLAY = "REPLAY"
    }
}
