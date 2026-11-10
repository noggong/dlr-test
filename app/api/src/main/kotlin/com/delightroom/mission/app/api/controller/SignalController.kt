package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.FailureResponse
import com.delightroom.mission.app.api.dto.PassRequest
import com.delightroom.mission.app.api.dto.PassResponse
import com.delightroom.mission.app.api.dto.SolveRequest
import com.delightroom.mission.app.api.dto.SolveResponse
import com.delightroom.mission.common.error.NotImplementedYet
import com.delightroom.mission.core.service.SignalService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** solve-signal (FR-4 · §5.4 ~ §5.7). */
@RestController
@RequestMapping("/api/v1/games/today")
class SignalController(
    private val signalService: SignalService,
) {
    @PostMapping("/solves")
    suspend fun solve(@RequestBody request: SolveRequest): SolveResponse {
        // TODO(solve-signal): signalService.solve
        throw NotImplementedYet("solve-signal")
    }

    @PostMapping("/passes")
    suspend fun pass(@RequestBody request: PassRequest): PassResponse {
        // TODO(solve-signal): signalService.pass
        throw NotImplementedYet("solve-signal")
    }

    /** 로컬 스토리지가 빈 것을 감지한 클라이언트의 부정 실패 처리 요청(R-F2). */
    @PostMapping("/failures")
    suspend fun fail(): FailureResponse {
        // TODO(solve-signal): signalService.failByCheat
        throw NotImplementedYet("solve-signal")
    }
}
