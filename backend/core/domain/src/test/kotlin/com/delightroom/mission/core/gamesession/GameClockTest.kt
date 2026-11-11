package com.delightroom.mission.core.gamesession

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.testfixture.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/** `GameClock` — 게임 날짜(SPEC GS1) · 경계 시각(LLD game-session §1). 고정 데이터 D = 2026-11-12(ORACLE §0). */
class GameClockTest {
    private val properties = GameProperties()
    private val clock = MutableClock(at("2026-11-12T06:00:00+09:00"), properties.zone)
    private val gameClock = GameClock(properties, clock)

    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private fun us(text: String): Long {
        val i = at(text)
        return i.epochSecond * 1_000_000L + i.nano / 1_000L
    }

    @Test
    fun `O6 lateUs(D) 는 D 07시 00분 03초 KST 의 epoch µs`() {
        assertThat(gameClock.lateUs(D)).isEqualTo(us("2026-11-12T07:00:03+09:00"))
        assertThat(gameClock.lateUs(D)).isEqualTo(1_794_434_403_000_000L)
        assertThat(gameClock.lateAt(D)).isEqualTo(at("2026-11-12T07:00:03+09:00"))
    }

    @Test
    fun `O6 (바탕) 지각 기준은 game_late-grace 를 따른다`() {
        val custom = GameClock(GameProperties(lateGrace = Duration.ofMillis(1500)), clock)

        assertThat(custom.lateUs(D)).isEqualTo(us("2026-11-12T07:00:01.5+09:00"))
    }

    @Test
    fun `O5 (바탕) 경계 시각 — 07시 시작 · 08시 마감의 Instant 와 µs`() {
        assertThat(gameClock.startAt(D)).isEqualTo(at("2026-11-12T07:00:00+09:00"))
        assertThat(gameClock.deadlineAt(D)).isEqualTo(at("2026-11-12T08:00:00+09:00"))
        assertThat(gameClock.startUs(D)).isEqualTo(us("2026-11-12T07:00:00+09:00"))
        assertThat(gameClock.deadlineUs(D)).isEqualTo(us("2026-11-12T08:00:00+09:00"))
        assertThat(gameClock.deadlineUs(D) - gameClock.startUs(D)).isEqualTo(3_600_000_000L)
    }

    @Test
    fun `O29 (바탕) prepare 시각은 06시 30분 · 06시 50분 KST`() {
        assertThat(gameClock.prepareAt(D)).containsExactly(
            at("2026-11-12T06:30:00+09:00"),
            at("2026-11-12T06:50:00+09:00"),
        )
    }

    @Test
    fun `O30 (바탕) 명단 키 만료는 다음 날 08시 KST`() {
        assertThat(gameClock.rosterExpireAt(D)).isEqualTo(at("2026-11-13T08:00:00+09:00"))
    }

    @Test
    fun `O3 게임 정보 날짜는 08시 정각부터 다음 날`() {
        assertThat(gameClock.infoDate(at("2026-11-12T00:00:00+09:00"))).isEqualTo(D)
        assertThat(gameClock.infoDate(at("2026-11-12T06:55:00+09:00"))).isEqualTo(D)
        assertThat(gameClock.infoDate(at("2026-11-12T07:59:59.999999+09:00"))).isEqualTo(D)
        assertThat(gameClock.infoDate(at("2026-11-12T08:00:00+09:00"))).isEqualTo(D.plusDays(1))
        assertThat(gameClock.infoDate(at("2026-11-12T23:59:59.999999+09:00"))).isEqualTo(D.plusDays(1))
    }

    @Test
    fun `O3 문제은행 · 진입 신호 날짜는 서버 시각의 KST 날짜`() {
        assertThat(gameClock.playDate(at("2026-11-12T08:00:00+09:00"))).isEqualTo(D)
        assertThat(gameClock.playDate(at("2026-11-12T23:59:59.999999+09:00"))).isEqualTo(D)
        // UTC 로는 전날 — KST 로 고른다
        assertThat(gameClock.playDate(at("2026-11-11T15:00:00Z"))).isEqualTo(D)
        assertThat(gameClock.playDate(at("2026-11-11T14:59:59.999999Z"))).isEqualTo(D.minusDays(1))
    }

    @Test
    fun `O1 (바탕) now 는 시각 주입 오프셋을 더한다`() {
        val shifted = GameClock(GameProperties(clockOffset = Duration.ofMinutes(55)), clock)

        assertThat(gameClock.now()).isEqualTo(at("2026-11-12T06:00:00+09:00"))
        assertThat(shifted.now()).isEqualTo(at("2026-11-12T06:55:00+09:00"))
        clock.advance(Duration.ofMinutes(10))
        assertThat(shifted.infoDate(shifted.now())).isEqualTo(D)
    }

    @Test
    fun `O6 epochMicros 는 µs 아래를 내림한다`() {
        assertThat(GameClock.epochMicros(Instant.ofEpochSecond(1, 999))).isEqualTo(1_000_000L)
        assertThat(GameClock.epochMicros(Instant.ofEpochSecond(1, 1_999))).isEqualTo(1_000_001L)
    }

    @Test
    fun `O29 (바탕) 잘못된 game 설정은 기동 때 실패한다`() {
        assertThatThrownBy { GameProperties(prepareTimes = listOf(LocalTime.of(6, 50), LocalTime.of(6, 30))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GameProperties(prepareTimes = listOf(LocalTime.of(7, 0))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GameProperties(prepareTimes = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GameProperties(bankSizePerDifficulty = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GameProperties(lateGrace = Duration.ofSeconds(-1)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
    }
}
