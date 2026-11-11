package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.auth.AuthPrincipal
import com.delightroom.mission.app.api.auth.CurrentUser
import com.delightroom.mission.app.api.dto.GameInfoResponse
import com.delightroom.mission.app.api.dto.QuestionBankResponse
import com.delightroom.mission.app.api.dto.ShownRequest
import com.delightroom.mission.app.api.dto.ShownResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.service.GameService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** game-session (FR-1 · FR-3 · §5.2) — 계약은 docs/spec/game-session.md. */
@RestController
@RequestMapping("/api/v1/games/today")
class GameController(
    private val gameService: GameService,
    private val gameProperties: GameProperties,
) {
    @GetMapping
    suspend fun gameInfo(@CurrentUser user: AuthPrincipal): GameInfoResponse =
        GameInfoResponse.of(gameService.gameInfo(user.groupId), gameProperties.zone)

    /** 문제은행 받기 = 미션 시작(GS5). 상태를 바꾸므로 POST, 본문 없음. */
    @PostMapping("/question-bank")
    suspend fun questionBank(@CurrentUser user: AuthPrincipal): QuestionBankResponse =
        QuestionBankResponse.of(gameService.startMission(user.userId, user.groupId), gameProperties.zone)

    /** 진입 신호 — 이 번호의 지금 문제를 고정(GS9 ~ GS13). 같은 문제면 멱등 200. */
    @PostMapping("/shown")
    suspend fun shown(@RequestBody request: ShownRequest): ShownResponse {
        // TODO(game-session): 진입 신호
        throw NotImplementedYet("game-session")
    }
}
