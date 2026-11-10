package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.BoardResponse
import com.delightroom.mission.app.api.dto.ResultResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.BoardService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** realtime-board · deadline-result 의 조회(FR-5 · FR-7). */
@RestController
@RequestMapping("/api/v1/games")
class BoardController(
    private val boardService: BoardService,
) {
    @GetMapping("/today/board")
    suspend fun board(): BoardResponse {
        // TODO(realtime-board): boardService.board
        throw NotImplementedYet("realtime-board")
    }

    @GetMapping("/results/latest")
    suspend fun latestResult(): ResultResponse {
        // TODO(deadline-result): boardService.latestResult — 어느 날짜인지는 Q8
        throw NotImplementedYet("deadline-result")
    }
}
