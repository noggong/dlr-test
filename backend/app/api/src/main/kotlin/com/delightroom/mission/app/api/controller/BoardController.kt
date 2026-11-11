package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.auth.AuthPrincipal
import com.delightroom.mission.app.api.auth.CurrentUser
import com.delightroom.mission.app.api.dto.BoardResponse
import com.delightroom.mission.app.api.dto.ResultResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.service.BoardService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** realtime-board · deadline-result 의 조회(FR-5 · FR-7). */
@RestController
@RequestMapping("/api/v1/games")
class BoardController(
    private val boardService: BoardService,
    private val gameProperties: GameProperties,
) {
    @GetMapping("/today/board")
    suspend fun board(): BoardResponse {
        // TODO(realtime-board): boardService.board
        throw NotImplementedYet("realtime-board")
    }

    /** 가장 최근 결과(deadline-result SPEC §2 · DR1) — 결과가 한 번도 없으면 404 NO_RESULT_YET. */
    @GetMapping("/results/latest")
    suspend fun latestResult(@CurrentUser user: AuthPrincipal): ResultResponse =
        ResultResponse.of(boardService.latestResult(user.loginId, user.groupId), gameProperties.zone)
}
