package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * ORACLE realtime-board O8 · O9 · O11 — `POST /api/v1/realtime/tickets` 를 07:30(창 안)에 부른다.
 * 창 밖(O10 API 1건)은 [RealtimeTicketApiClosedTest]. 08:00:02 통과 · 08:00:03 거부 경계는 Lua 시각을 직접 잡는 `BoardReadLuaTest` 가 본다
 * (앱 테스트의 오프셋은 컨텍스트를 만들 때 한 번 계산하므로 부팅 시간 · 호스트와 Redis 컨테이너의 시계 차이만큼 어긋난다).
 */
class RealtimeTicketApiTest : RealtimeTicketApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at0730(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(7, 30))

        /** SPEC §4 응답 예시의 시각 모양 — 소수 6자리 · `+09:00`. */
        private val MICROS_KST = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+09:00$""")
    }

    @Test
    fun `O8 창 안 발급은 201 · 43자 티켓 · 만료 약 30초 뒤 · TTL 29~30 · 값이 요청 유저`(): Unit = runBlocking {
        fourMembers()
        val actual = body(postTicket(access(MINJI)), 201)
        val respondedAt = redisNow()

        assertThat(actual.keys).containsExactly("ticket", "expiresAt")
        val ticket = actual["ticket"] as String
        assertThat(ticket).matches(TICKET_SHAPE.pattern)
        val expiresAtText = actual["expiresAt"] as String
        assertThat(expiresAtText).matches(MICROS_KST.pattern)
        val untilExpiry = Duration.between(respondedAt, OffsetDateTime.parse(expiresAtText).toInstant())
        assertThat(untilExpiry).isBetween(Duration.ofSeconds(29), Duration.ofSeconds(31))

        val key = redisKeys.ticket(ticket)
        assertThat(ticketKeys()).containsExactly(key)
        assertThat(redis.getExpire(key).awaitSingle().seconds).isBetween(29L, 30L)
        val stored: Map<String, String> = json.readValue(redis.opsForValue().get(key).awaitSingle())
        assertThat(stored).containsEntry("uid", "$MINJI").containsEntry("gid", "${gid.value}")
            .containsEntry("date", "${BoardFixtures.DATE}")
            .containsEntry("exp", "${GameClock.epochMicros(OffsetDateTime.parse(expiresAtText).toInstant())}")
    }

    @Test
    fun `O8 미션 시작 전 · 그룹 Hash 없이도 발급한다 — 티켓은 명단만 본다`() {
        roster(MEMBERS)
        body(postTicket(access(MINJI)), 201)
        assertThat(ticketKeys()).hasSize(1)
    }

    @Test
    fun `RB4 같은 유저가 여러 번 받으면 티켓이 따로 생긴다`() {
        fourMembers()
        val first = body(postTicket(access(MINJI)), 201)["ticket"]
        val second = body(postTicket(access(MINJI)), 201)["ticket"]
        assertThat(first).isNotEqualTo(second)
        assertThat(ticketKeys()).hasSize(2)
    }

    @Test
    fun `O9 쿠키 없음 · 만료 토큰은 401 UNAUTHORIZED 이고 tk 키 0개`() {
        fourMembers()
        assertThat(body(postTicket(null), 401)["code"]).isEqualTo("UNAUTHORIZED")
        val expired = access(MINJI, issuedAt = gameProperties.now(clock).minus(Duration.ofHours(2)))
        assertThat(body(postTicket(expired), 401)["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(ticketKeys()).isEmpty()
    }

    @Test
    fun `O11 명단에 없는 유저는 403 NOT_PARTICIPANT 이고 tk 키 0개`() {
        fourMembers()
        assertThat(body(postTicket(access(999L)), 403)["code"]).isEqualTo("NOT_PARTICIPANT")
        assertThat(ticketKeys()).isEmpty()
    }

    @Test
    fun `O11 그날 그룹원 1명이면 409 SOLO_GROUP 이고 tk 키 0개`() {
        roster(mapOf(MINJI to "minji"))
        assertThat(body(postTicket(access(MINJI)), 409)["code"]).isEqualTo("SOLO_GROUP")
        assertThat(ticketKeys()).isEmpty()
    }

    @Test
    fun `RB3 명단 확정 전이면 409 GAME_NOT_OPEN 이고 tk 키 0개`() {
        assertThat(body(postTicket(access(MINJI)), 409)["code"]).isEqualTo("GAME_NOT_OPEN")
        assertThat(ticketKeys()).isEmpty()
    }
}
