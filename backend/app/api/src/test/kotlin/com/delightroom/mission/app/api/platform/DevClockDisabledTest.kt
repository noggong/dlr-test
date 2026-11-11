package com.delightroom.mission.app.api.platform

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.app.api.registeredRoutes
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.service.DemoClockService
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.reactive.server.WebTestClient

/** ORACLE platform O44 — 앱 기본(운영)은 데모 시계가 꺼져 시각 이동 라우트가 없고 이동량이 0 이다(SPEC platform P9). RouteSmokeTest 와 컨텍스트를 나눠 쓴다. */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
class DevClockDisabledTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val game: GameProperties,
    ) {
        private val client = WebTestClient.bindToApplicationContext(context).build()

        @Test
        fun `O44 꺼진 앱은 dev clock 라우트가 없고 인증 예외도 없어 쿠키 없이는 401 · DemoClockService 빈이 없고 이동량이 0`() {
            assertThat(registeredRoutes(context).filter { it.contains("/dev/") }).isEmpty()
            client.get().uri("/api/v1/dev/clock").exchange().expectStatus().isUnauthorized
            client.post().uri("/api/v1/dev/clock").contentType(MediaType.APPLICATION_JSON).bodyValue("""{"to":"deadline"}""")
                .exchange().expectStatus().isUnauthorized
            assertThat(context.getBeanNamesForType(DemoClockService::class.java)).isEmpty()
            assertThat(game.shift.micros).isZero()
        }
    }
