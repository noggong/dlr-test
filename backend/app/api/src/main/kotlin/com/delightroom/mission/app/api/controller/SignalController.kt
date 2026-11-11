package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.auth.AuthPrincipal
import com.delightroom.mission.app.api.auth.CurrentUser
import com.delightroom.mission.app.api.dto.FailureResponse
import com.delightroom.mission.app.api.dto.PassRequest
import com.delightroom.mission.app.api.dto.PassResponse
import com.delightroom.mission.app.api.dto.SolveRequest
import com.delightroom.mission.app.api.dto.SolveResponse
import com.delightroom.mission.core.service.SignalService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** solve-signal (FR-4 · §5.4 ~ §5.7) — 계약은 docs/spec/solve-signal.md. 본문 검사(400)가 서비스 · Redis 보다 먼저다. */
@RestController
@RequestMapping("/api/v1/games/today")
class SignalController(
    private val signalService: SignalService,
) {
    @PostMapping("/solves")
    suspend fun solve(@CurrentUser user: AuthPrincipal, @RequestBody request: SolveRequest): SolveResponse {
        val signal = request.toSignal()
        return SolveResponse.of(signalService.solve(user.userId, user.groupId, signal))
    }

    @PostMapping("/passes")
    suspend fun pass(@CurrentUser user: AuthPrincipal, @RequestBody request: PassRequest): PassResponse {
        val signal = request.toSignal()
        return PassResponse.of(signalService.pass(user.userId, user.groupId, signal), signal.questionNo)
    }

    /** 로컬 스토리지가 빈 것을 감지한 클라이언트의 부정 실패 처리 요청(R-F2). 본문은 보내도 무시한다. */
    @PostMapping("/failures")
    suspend fun fail(@CurrentUser user: AuthPrincipal): FailureResponse =
        FailureResponse.of(signalService.failByCheat(user.userId, user.groupId))
}
