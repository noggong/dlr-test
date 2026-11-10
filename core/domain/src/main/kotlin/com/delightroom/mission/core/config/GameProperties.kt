package com.delightroom.mission.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** 게임 규칙의 수치(prd §1 · §3 · §5). 기본값은 PRD 원문 값이다. */
@ConfigurationProperties("game")
data class GameProperties(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    val startTime: LocalTime = LocalTime.of(7, 0),
    val deadlineTime: LocalTime = LocalTime.of(8, 0),
    /** 마감 직전 제출 가드 유예(결정 D1 · R-D2). */
    val submitGrace: Duration = Duration.ofSeconds(3),
    val minGroupSize: Int = 2,
    val maxGroupSize: Int = 10,
    val questionsPerMission: Int = 3,
    val passesPerDay: Int = 1,
)
