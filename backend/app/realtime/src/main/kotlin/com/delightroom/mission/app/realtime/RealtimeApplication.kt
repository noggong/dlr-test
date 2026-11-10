package com.delightroom.mission.app.realtime

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.delightroom.mission.core", "com.delightroom.mission.app.realtime"])
class RealtimeApplication

fun main(args: Array<String>) {
    runApplication<RealtimeApplication>(*args)
}
