package com.delightroom.mission.infra.redis.gamesession

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.MutableClock
import com.delightroom.mission.infra.redis.QuestionBankCodec
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisQuestionBankCache
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * `bank:{<uid>}:<date>` — [RedisQuestionBankCache](LLD game-session v6 §2.1 · §2.3). 문제은행 API 기준 O7 · O14 · O15 · I2 가 기대는
 * 값 형식 · 「없을 때만」 · 만료(게임 시각 기준 남은 ms)를 받친다.
 */
class QuestionBankCacheTest {
    private val redis = RedisTestContainer.template
    private val properties = GameProperties()
    private val keys = RedisKeys(properties)

    /** 앱 게임 시각 D 07:05 — Redis 실제 시각(오늘)과 하루 넘게 어긋나도 만료는 08:00 까지 남은 55분이다. */
    private val clock = MutableClock(OffsetDateTime.parse("${D}T07:05:00+09:00").toInstant(), properties.zone)
    private val gameClock = GameClock(properties, clock)
    private val cache = RedisQuestionBankCache(redis, keys, QuestionBankCodec(), gameClock)

    private fun bank(seed: Long) = QuestionBank(
        mapOf(
            Difficulty.ONE to listOf(seed + 3, seed + 1, seed + 2),
            Difficulty.TWO to listOf(seed + 20),
            Difficulty.THREE to listOf(seed + 31, seed + 30),
        ),
    )

    private fun newUser() = UserId(UID.incrementAndGet())

    @Test
    fun `O15 (바탕) 없을 때만 넣고 값은 JSON 난이도별 배열 · 순서 그대로 · 남은 시간은 게임 시각부터 08시까지`(): Unit = runBlocking {
        val uid = newUser()

        assertThat(cache.putIfAbsent(uid, D, bank(100), gameClock.deadlineAt(D))).isTrue()

        val raw = redis.opsForValue().get(keys.bank(uid, D)).awaitSingle()
        assertThat(raw).isEqualTo("""{"1":[103,101,102],"2":[120],"3":[131,130]}""")
        assertThat(cache.get(uid, D)).isEqualTo(bank(100))
        val ttl = redis.getExpire(keys.bank(uid, D)).awaitSingle()
        assertThat(ttl).isBetween(Duration.ofMinutes(55).minusSeconds(1), Duration.ofMinutes(55))
    }

    @Test
    fun `O7 (바탕) 이미 있으면 false · 처음 값 그대로 — 만료도 늘지 않는다`(): Unit = runBlocking {
        val uid = newUser()
        cache.putIfAbsent(uid, D, bank(100), gameClock.deadlineAt(D))
        clock.set(OffsetDateTime.parse("${D}T07:30:00+09:00").toInstant())

        assertThat(cache.putIfAbsent(uid, D, bank(500), gameClock.deadlineAt(D))).isFalse()

        assertThat(cache.get(uid, D)).isEqualTo(bank(100))
        assertThat(redis.getExpire(keys.bank(uid, D)).awaitSingle()).isGreaterThan(Duration.ofMinutes(50))
    }

    @Test
    fun `O14 (바탕) 없는 키는 null`(): Unit = runBlocking {
        assertThat(cache.get(newUser(), D)).isNull()
    }

    @Test
    fun `I2 (바탕) 서로 다른 은행 100개를 동시에 넣어도 하나만 true · 키 값은 그 하나`(): Unit = runBlocking {
        repeat(5) {
            val uid = newUser()
            val wins = coroutineScope {
                (1..100).map { i -> async { cache.putIfAbsent(uid, D, bank(i * 1_000L), gameClock.deadlineAt(D)) to i } }.awaitAll()
            }.filter { it.first }

            assertThat(wins).hasSize(1)
            assertThat(cache.get(uid, D)).isEqualTo(bank(wins.single().second * 1_000L))
        }
    }

    @Test
    fun `O15 (바탕) 08시 직전 · 지난 만료도 1 ms 로 넣는다 — 오류 없이 곧 사라진다`(): Unit = runBlocking {
        val uid = newUser()
        clock.set(OffsetDateTime.parse("${D}T08:00:01+09:00").toInstant())

        assertThat(cache.putIfAbsent(uid, D, bank(1), gameClock.deadlineAt(D))).isTrue()

        Thread.sleep(20)
        assertThat(redis.opsForValue().get(keys.bank(uid, D)).awaitSingleOrNull()).isNull()
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val UID = AtomicLong(Math.floorMod(System.nanoTime(), 1_000_000L) * 1_000L + 9_000_000_000L)
    }
}
