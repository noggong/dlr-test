package com.delightroom.mission.common.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ConnectException

/**
 * ORACLE platform E1(분류 쪽) — 원인 사슬 · 순환 · 표지 상속. Spring · 드라이버의 실제 타입은 app:api `ErrorResponseTest` 가 본다.
 */
class FailureClassifierTest {
    private class ChannelLost : DependencyUnavailableException("구독이 끊겼다")

    /** `cause` 를 나중에 바꿀 수 있는 예외 — 순환 사슬을 만든다. */
    private class Loop(message: String) : RuntimeException(message) {
        var next: Throwable? = null
        override val cause: Throwable? get() = next
    }

    @Test
    fun `E1 BusinessException 은 자기 코드다`() {
        assertThat(FailureClassifier.errorCodeOf(BusinessException(ErrorCode.GAME_NOT_OPEN))).isEqualTo(ErrorCode.GAME_NOT_OPEN)
    }

    @Test
    fun `E1 표지 예외와 그 하위 타입은 503 이다`() {
        assertThat(FailureClassifier.errorCodeOf(DependencyUnavailableException("redis"))).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(FailureClassifier.errorCodeOf(ChannelLost())).isEqualTo(ErrorCode.UNAVAILABLE)
    }

    @Test
    fun `E1 원인 사슬 깊은 곳의 연결 실패도 503 이다`() {
        val wrapped = IllegalStateException("겉", RuntimeException("가운데", ConnectException("connection refused")))
        assertThat(FailureClassifier.errorCodeOf(wrapped)).isEqualTo(ErrorCode.UNAVAILABLE)
    }

    @Test
    fun `E1 순환하는 원인 사슬도 끝나고 장애가 없으면 500 이다`() {
        val a = Loop("a")
        val b = Loop("b").also { it.next = a }
        a.next = b
        assertThat(FailureClassifier.errorCodeOf(a)).isEqualTo(ErrorCode.INTERNAL_ERROR)
        val c = Loop("c").also { it.next = ConnectException("refused") }
        val d = Loop("d").also { it.next = c }
        assertThat(FailureClassifier.errorCodeOf(d)).isEqualTo(ErrorCode.UNAVAILABLE)
    }

    @Test
    fun `E2 그 밖의 예외는 500 이다`() {
        assertThat(FailureClassifier.errorCodeOf(IllegalStateException("db-host:5432"))).isEqualTo(ErrorCode.INTERNAL_ERROR)
        assertThat(FailureClassifier.errorCodeOf(NullPointerException())).isEqualTo(ErrorCode.INTERNAL_ERROR)
        assertThat(FailureClassifier.errorCodeOf(Error("oom"))).isEqualTo(ErrorCode.INTERNAL_ERROR)
    }
}
