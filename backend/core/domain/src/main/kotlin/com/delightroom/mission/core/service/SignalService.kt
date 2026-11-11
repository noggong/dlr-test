package com.delightroom.mission.core.service

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.FailCommand
import com.delightroom.mission.core.domain.progress.PassCommand
import com.delightroom.mission.core.domain.progress.PassSignal
import com.delightroom.mission.core.domain.progress.SignalRejection
import com.delightroom.mission.core.domain.progress.SignalReply
import com.delightroom.mission.core.domain.progress.SignalResult
import com.delightroom.mission.core.domain.progress.SignalTarget
import com.delightroom.mission.core.domain.progress.SolveCommand
import com.delightroom.mission.core.domain.progress.SolveSignal
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.ProgressRepository
import org.springframework.stereotype.Service

/**
 * solve-signal — 풀이 · 패스 · 부정 실패 신호(FR-4 · §5.4 ~ §5.7 · LLD §3.0 · §3.4).
 * 판정 · 반영 · 그룹 판정 · 기록 · 전달은 Lua 한 번이 한다. 여기서는 날짜 · 시간 창을 계산하고 거부 코드를 오류로 바꾼다.
 */
@Service
class SignalService(
    private val progresses: ProgressRepository,
    private val rosters: RosterService,
    private val gameClock: GameClock,
    private val properties: GameProperties,
) {
    suspend fun solve(userId: UserId, groupId: GroupId, signal: SolveSignal): SignalResult =
        signal(userId, groupId, beforeOpen = ErrorCode.GAME_NOT_OPEN) { progresses.applySolve(SolveCommand(it, signal)) }

    /** 받아들이면 [SignalResult.passedAtQuestionNo] 가 있다. */
    suspend fun pass(userId: UserId, groupId: GroupId, signal: PassSignal): SignalResult =
        signal(userId, groupId, beforeOpen = ErrorCode.GAME_NOT_OPEN) { progresses.applyPass(PassCommand(it, signal)) }
            .also { checkNotNull(it.passedAtQuestionNo) { "pass 가 받아들였는데 패스 번호가 없다: $it" } }

    /** 부정 실패(R-F2). 07:00 전 · 명단 없음은 시작 전이다(D14). */
    suspend fun failByCheat(userId: UserId, groupId: GroupId): SignalResult =
        signal(userId, groupId, beforeOpen = ErrorCode.MISSION_NOT_STARTED) { progresses.applyCheatFail(FailCommand(it)) }

    /**
     * 공통 흐름(LLD §3.0 · §3.4) — Lua 를 부르고, `NO_ROSTER` 면 07:00 전은 [beforeOpen],
     * 07:00 이후는 명단을 만든 뒤(game-session — 「없을 때만」) 한 번 더 부른다(SPEC SS15).
     */
    private suspend fun signal(
        userId: UserId,
        groupId: GroupId,
        beforeOpen: ErrorCode,
        apply: suspend (SignalTarget) -> SignalReply,
    ): SignalResult {
        val target = target(userId, groupId)
        val first = apply(target)
        if (first !is SignalRejection || first.code != SignalRejection.NO_ROSTER) return accept(first)

        // Lua 의 t(Redis 시각)와 앱 시계가 경계에서 어긋나도 07:00 전 명단을 만들지 않는다(RosterService 의 전제).
        val openAt = gameClock.startAt(target.date)
        if (first.arrivedAt < openAt || gameClock.now() < openAt) throw BusinessException(beforeOpen)
        rosters.findOrCreate(groupId, target.date)

        val second = apply(target)
        if (second is SignalRejection && second.code == SignalRejection.NO_ROSTER) {
            // 명단을 만들었는데도 없다 — 명단이 없으면 시작한 유저도 없다(LLD §3.4)
            log.log(System.Logger.Level.WARNING, "명단을 만든 뒤에도 NO_ROSTER: gid=${groupId.value} date=${target.date}")
            throw BusinessException(ErrorCode.MISSION_NOT_STARTED)
        }
        return accept(second)
    }

    private fun accept(reply: SignalReply): SignalResult = when (reply) {
        is SignalResult -> reply
        is SignalRejection -> throw BusinessException(errorCodeOf(reply.code))
    }

    /** 날짜는 앱 게임 시각의 KST 날짜(07:00 ~ 08:00:03 에 바뀌지 않는다), 시간 창은 그 날짜의 epoch µs. */
    private fun target(userId: UserId, groupId: GroupId): SignalTarget {
        val date = gameClock.playDate(gameClock.now())
        return SignalTarget(
            userId = userId,
            groupId = groupId,
            date = date,
            openAtUs = gameClock.startUs(date),
            deadlineAtUs = gameClock.deadlineUs(date),
            graceEndAtUs = GameClock.epochMicros(properties.graceEndAt(date)),
        )
    }

    private companion object {
        /** core 서비스는 프레임워크 로깅에 기대지 않는다(ArchitectureTest) — JDK System.Logger. */
        val log: System.Logger = System.getLogger(SignalService::class.java.name)

        /** Lua 가 낼 수 있는 거부 코드(LLD §3.1 ~ §3.3 출력 [1]) — 그 밖의 값은 계약 위반이다. */
        val REJECTIONS: Map<String, ErrorCode> = listOf(
            ErrorCode.NOT_PARTICIPANT,
            ErrorCode.SOLO_GROUP,
            ErrorCode.MISSION_NOT_STARTED,
            ErrorCode.MISSION_ALREADY_FINISHED,
            ErrorCode.GAME_NOT_OPEN,
            ErrorCode.SUBMISSION_CLOSED,
            ErrorCode.QUESTION_OUT_OF_ORDER,
            ErrorCode.QUESTION_MISMATCH,
            ErrorCode.PASS_ALREADY_USED,
        ).associateBy { it.name }

        fun errorCodeOf(code: String): ErrorCode =
            checkNotNull(REJECTIONS[code]) { "신호 Lua 가 모르는 거부 코드를 돌려줬다: $code" }
    }
}
