package com.delightroom.mission.core.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/** LLD auth-signup §1 · §5 — `auth.jwt-secret` 이 없거나 UTF-8 32바이트 미만이면 기동(바인딩) 실패. */
class AuthPropertiesTest {
    @EnableConfigurationProperties(AuthProperties::class)
    class PropertiesOnly

    private fun runner(vararg pairs: String) =
        ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java).withPropertyValues(*pairs)

    @Test
    fun `32바이트 키면 뜨고 기본값은 1시간 · 2시간 · cost 10 · Secure`() {
        runner("auth.jwt-secret=${"k".repeat(32)}").run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(AuthProperties::class.java)
            assertThat(properties.accessTtl).isEqualTo(Duration.ofHours(1))
            assertThat(properties.refreshTtl).isEqualTo(Duration.ofHours(2))
            assertThat(properties.bcryptCost).isEqualTo(10)
            assertThat(properties.cookieSecure).isTrue()
            assertThat(properties.toString()).doesNotContain("k".repeat(32))
        }
    }

    @Test
    fun `키가 없거나 31바이트면 기동이 실패한다`() {
        runner().run { assertThat(it).hasFailed() }
        runner("auth.jwt-secret=${"k".repeat(31)}").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("32바이트")
        }
    }

    @Test
    fun `길이는 문자 수가 아니라 UTF-8 바이트 수로 센다`() {
        // 한글 11자 = 33바이트 — 문자 수는 32 미만이어도 통과
        runner("auth.jwt-secret=${"가".repeat(11)}").run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `cookie-secure=false 로 Secure 를 끈다`() {
        runner("auth.jwt-secret=${"k".repeat(32)}", "auth.cookie-secure=false").run { context ->
            assertThat(context.getBean(AuthProperties::class.java).cookieSecure).isFalse()
        }
    }
}
