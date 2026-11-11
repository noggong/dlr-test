package com.delightroom.mission.app.worker

import com.delightroom.mission.core.service.AuthService
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `worker` — 웹 서버 없이(`spring.main.web-application-type: none`) 잡과 persister 소비 루프를 돈다(HLD platform §2 · LLD platform §1).
 * 인증은 api 만 한다 — [AuthService] 가 기대는 해시 · 토큰 구현이 app:api 에 있어 싣지 않는다.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = ["com.delightroom.mission.core", "com.delightroom.mission.app.worker"],
    excludeFilters = [
        // @SpringBootApplication 의 기본 제외 필터 둘 — 직접 단 @ComponentScan 이 그것을 대신하므로 다시 적는다
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [AuthService::class]),
    ],
)
@ConfigurationPropertiesScan("com.delightroom.mission.app.worker")
@EnableScheduling
class WorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkerApplication>(*args)
}
