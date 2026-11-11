package com.delightroom.mission.app.api.controller

import com.delightroom.mission.app.api.dto.DevClockRequest
import com.delightroom.mission.app.api.dto.DevClockResponse
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.service.DemoClockService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로컬 데모 전용 시각 보기 · 옮기기(prd D25 · SPEC platform §6.3). `game.demo.enabled=true` 일 때만 라우트가 있다 — 꺼지면 404(P9).
 * 인증 필터는 이 경로를 보지 않는다(P14 — `AccessTokenWebFilter` 공개 경로).
 */
@RestController
@RequestMapping("/api/v1/dev/clock")
@ConditionalOnProperty(prefix = "game.demo", name = ["enabled"], havingValue = "true")
class DevClockController(
    private val demoClock: DemoClockService,
    private val gameClock: GameClock,
    private val gameProperties: GameProperties,
) {
    @GetMapping
    fun clock(): DevClockResponse = respond(demoClock.snapshot())

    /** 본문 검사(400)가 이동 검사(409)보다 먼저다. */
    @PostMapping
    suspend fun move(@RequestBody request: DevClockRequest): DevClockResponse = respond(demoClock.move(request.toTarget()))

    private fun respond(s: DemoClockService.Snapshot): DevClockResponse =
        DevClockResponse.of(s, gameProperties, gameClock.infoDate(s.gameNow))
}
