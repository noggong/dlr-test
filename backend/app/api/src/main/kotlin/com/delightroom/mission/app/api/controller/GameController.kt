package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.GameInfoResponse
import com.delightroom.mission.app.api.dto.QuestionBankResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.GameService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** game-session (FR-1 · FR-3 · §5.2). */
@RestController
@RequestMapping("/api/v1/games/today")
class GameController(
    private val gameService: GameService,
) {
    @GetMapping
    suspend fun gameInfo(): GameInfoResponse {
        // TODO(game-session): gameService.gameInfo
        throw NotImplementedYet("game-session")
    }

    /** 문제은행 받기 = 미션 시작(R-B1). 상태를 바꾸므로 POST. */
    @PostMapping("/question-bank")
    suspend fun questionBank(): QuestionBankResponse {
        // TODO(game-session): gameService.startMission
        throw NotImplementedYet("game-session")
    }
}
