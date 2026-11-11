package com.delightroom.mission.app.api.dto

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.DemoClockRules
import com.delightroom.mission.core.service.DemoClockService
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** `POST /api/v1/dev/clock` — `to`(프리셋) · `at`(절대 게임 시각) 중 하나만(SPEC platform §6.3). */
data class DevClockRequest(
    /** `start` · `deadline`. */
    val to: Any? = null,
    /** ISO-8601 · 오프셋 필수. */
    val at: Any? = null,
) {
    /** 본문 규칙 — 어기면 `400 INVALID_REQUEST`. */
    fun toTarget(): DemoClockService.Target {
        if ((to == null) == (at == null)) invalid()
        if (to != null) {
            val preset = when (to) {
                "start" -> DemoClockRules.Preset.START
                "deadline" -> DemoClockRules.Preset.DEADLINE
                else -> invalid()
            }
            return DemoClockService.Target.Preset(preset)
        }
        val raw = at as? String ?: invalid()
        val parsed = try {
            OffsetDateTime.parse(raw)
        } catch (_: DateTimeParseException) {
            invalid()
        }
        return DemoClockService.Target.At(parsed.toInstant())
    }

    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_REQUEST)
}

/** `GET` · `POST /api/v1/dev/clock` `200` — 지금 게임 시각(SPEC platform §6.3). */
data class DevClockResponse(
    val serverTime: OffsetDateTime,
    val realTime: OffsetDateTime,
    /** 런타임 이동량(초 · 소수) — `game.clock-offset` 은 빼고. */
    val shiftSeconds: BigDecimal,
    /** 게임 정보의 날짜(GS1). */
    val gameDate: LocalDate,
    val startAt: OffsetDateTime,
    val deadlineAt: OffsetDateTime,
    val rankingFinalAt: OffsetDateTime,
    /** 지금 옮길 수 있는 가장 늦은 게임 시각(P12). */
    val moveLimit: OffsetDateTime,
) {
    companion object {
        fun of(s: DemoClockService.Snapshot, game: GameProperties, gameDate: LocalDate): DevClockResponse {
            val zone = game.zone
            return DevClockResponse(
                serverTime = s.gameNow.atZone(zone).toOffsetDateTime(),
                realTime = s.realNow.atZone(zone).toOffsetDateTime(),
                shiftSeconds = BigDecimal.valueOf(s.shift.seconds).add(BigDecimal.valueOf(s.shift.nano.toLong(), 9)).stripTrailingZeros(),
                gameDate = gameDate,
                startAt = game.startAt(gameDate).atZone(zone).toOffsetDateTime(),
                deadlineAt = game.deadlineAt(gameDate).atZone(zone).toOffsetDateTime(),
                rankingFinalAt = game.graceEndAt(gameDate).atZone(zone).toOffsetDateTime(),
                moveLimit = s.moveLimit.atZone(zone).toOffsetDateTime(),
            )
        }
    }
}
