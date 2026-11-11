package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.common.error.ErrorCode
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
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.ProgressRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.LocalDate

/**
 * 신호 Lua `solve` · `pass` · `fail` 을 부르고 반환 배열을 해석한다(LLD solve-signal §3.1 ~ §3.3 · §3.5).
 * `EVALSHA` 는 한 번만 — `NOSCRIPT` 재적재 1회는 [RedisScripts] 가 한다.
 */
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

    /** `shown.lua`(LLD game-session §4.3) — 문제 캐시 판정(`qok`)은 앱이, 나머지 판정 · 고정 · 이벤트는 Lua 한 실행이 한다. */
    override suspend fun markShown(
        userId: UserId,
        groupId: GroupId,
        date: LocalDate,
        questionNo: Int,
        questionId: QuestionId,
        internalId: Long?,
    ): ShownOutcome {
        require(questionNo in 1..3) { "questionNo 는 1 · 2 · 3 이다: $questionNo" }
        val reply = scripts.eval(
            "shown",
            listOf(keys.group(groupId, date), keys.members(groupId, date), keys.events(groupId)),
            listOf(
                userId.value.toString(),
                questionNo.toString(),
                questionId.value,
                internalId?.toString() ?: "",
                if (internalId != null) "1" else "0",
                gameClock.startUs(date).toString(),
                gameClock.deadlineUs(date).toString(),
                properties.clockOffsetUs.toString(),
                date.toString(),
                groupId.value.toString(),
            ),
        )
        return when (reply.firstOrNull()) {
            "OK" -> {
                check(reply.size == 4) { "shown 통과 반환은 {OK, p, st, created} 다: $reply" }
                ShownOutcome.Shown((reply[1] as String).toInt(), MemberStatus.valueOf(reply[2] as String), reply[3] == "1")
            }
            "NO_ROSTER" -> ShownOutcome.NoRoster
            "ERR" -> {
                check(reply.size == 2) { "shown 거부 반환은 {ERR, code} 다: $reply" }
                val code = SHOWN_REJECTIONS.firstOrNull { it.name == reply[1] }
                checkNotNull(code) { "shown 이 모르는 거부 코드를 돌려줬다: $reply" }
                ShownOutcome.Rejected(code)
            }
            else -> error("shown 반환의 첫 칸은 OK · NO_ROSTER · ERR 이다: $reply")
        }
    }

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

        /** `start.lua` 가 내는 거부 코드(SPEC GS14). */
        val START_REJECTIONS = listOf(
            ErrorCode.GAME_NOT_OPEN,
            ErrorCode.SUBMISSION_CLOSED,
            ErrorCode.NOT_PARTICIPANT,
            ErrorCode.SOLO_GROUP,
        )

        /** `shown.lua` 가 내는 거부 코드(SPEC GS9). */
        val SHOWN_REJECTIONS = START_REJECTIONS + listOf(
            ErrorCode.MISSION_NOT_STARTED,
            ErrorCode.MISSION_ALREADY_FINISHED,
            ErrorCode.QUESTION_MISMATCH,
            ErrorCode.QUESTION_OUT_OF_ORDER,
        )
    }
}
