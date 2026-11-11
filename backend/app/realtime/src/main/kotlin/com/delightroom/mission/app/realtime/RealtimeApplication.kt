package com.delightroom.mission.app.realtime

import com.delightroom.mission.core.service.AuthService
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

/** 인증(가입 · 로그인)은 api 만 한다 — [AuthService] 가 기대는 해시 · 토큰 구현이 app:api 에 있어 realtime 은 싣지 않는다. */
@SpringBootApplication
@ComponentScan(
    basePackages = ["com.delightroom.mission.core", "com.delightroom.mission.app.realtime"],
    excludeFilters = [
        // @SpringBootApplication 의 기본 제외 필터 둘 — 직접 단 @ComponentScan 이 그것을 대신하므로 다시 적는다
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [AuthService::class]),
    ],
)
class RealtimeApplication

fun main(args: Array<String>) {
    runApplication<RealtimeApplication>(*args)
}
