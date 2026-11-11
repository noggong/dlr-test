package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.question.BankDraw
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.QuestionBank
import com.delightroom.mission.core.domain.question.QuestionBankBuilder
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.infra.redis.RedisKeys
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.test.context.ContextConfiguration
import reactor.core.publisher.Flux
import java.time.LocalDate

/**
 * 은행 키 메모리(ORACLE game-session O32 — HLD §5 · LLD §2.3). 문제 난이도마다 1,000개(7자리 내부 ID 3,000개 — 시드 DB 가 아니라
 * 메모리의 ID 목록) · 유저 10,000명의 은행을 [QuestionBankBuilder] 로 뽑아 잡과 같은 [QuestionBankCache.putAllIfAbsent] 로 두고
 * 키마다 `MEMORY USAGE` 를 잰다(Lua 로 부른다 — Lettuce 의 범용 명령 실행은 정수 응답을 받지 못한다). 유저 PK 는 운영 규모(250만)의 자릿수로 둔다 — 키 이름 길이가 메모리에 들어간다.
 */
@EnabledIfSystemProperty(named = "game.load", matches = "true")
@SpringBootTest(
    properties = [
        "worker.persister.enabled=false",
        "worker.member.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class QuestionBankMemoryLoadTest
    @Autowired
    constructor(
        private val cache: QuestionBankCache,
        private val keys: RedisKeys,
        private val gameClock: GameClock,
        private val game: GameProperties,
        private val redis: ReactiveStringRedisTemplate,
    ) {
        private val d: LocalDate = LocalDate.now(LoadFixture.KST).plusDays(1)

        @Test
        fun `O32 문제 1000개씩 유저 10000명 은행 키 1개 평균 MEMORY USAGE 1024 B 이하`(): Unit = runBlocking {
            val ids = Difficulty.entries.associateWith { diff ->
                LongArray(QUESTIONS_PER_DIFFICULTY) { FIRST_QUESTION_ID + (diff.level - 1L) * QUESTIONS_PER_DIFFICULTY + it }
            }
            val users = (1..USERS).map { UserId(FIRST_UID + it) }
            for (chunk in users.chunked(1_000)) {
                val banks = chunk.associateWith { uid ->
                    when (val draw = QuestionBankBuilder.build(ids, emptySet(), game.bankSizePerDifficulty)) {
                        is BankDraw.Built -> draw.bank
                        is BankDraw.Exhausted -> error("1,000개에서 고갈일 수 없다: $draw")
                    }
                }
                assertThat(cache.putAllIfAbsent(d, banks, gameClock.deadlineAt(d))).isEqualTo(chunk.size)
            }

            val bankKeys = users.map { keys.bank(it, d) }
            val usage = Flux.fromIterable(bankKeys)
                .flatMapSequential({ key -> redis.execute(MEMORY_USAGE, listOf(key)).next() }, 64)
                .collectList().awaitSingle()
            assertThat(usage).hasSize(USERS)
            val avg = usage.average()
            val sample: QuestionBank = cache.get(users.first(), d)!!
            println(
                "[O32] keys=$USERS avgBytes=${"%.1f".format(avg)} minBytes=${usage.min()} maxBytes=${usage.max()} " +
                    "bankIds=${sample.idsByDifficulty.values.sumOf { it.size }} projected2.5M=${"%.2f".format(avg * 2_500_000 / 1e9)}GB",
            )
            Difficulty.entries.forEach { assertThat(sample.forDifficulty(it)).hasSize(game.bankSizePerDifficulty) }
            assertThat(avg).describedAs("은행 키 평균 바이트").isLessThanOrEqualTo(1_024.0)
        }

        private companion object {
            const val USERS = 10_000
            const val QUESTIONS_PER_DIFFICULTY = 1_000
            const val FIRST_UID = 2_500_000L

            /** 7자리 내부 ID — LLD §2.3 추정(ID 7자 안팎)과 같은 자릿수로 보수적으로 잰다. */
            const val FIRST_QUESTION_ID = 1_000_000L

            /** `MEMORY USAGE` — 키 이름 · 값 · Redis 객체 머리를 모두 센 바이트(문자열 값이라 표본 추정이 아닌 정확한 값). */
            val MEMORY_USAGE: RedisScript<Long> = RedisScript.of("return redis.call('MEMORY', 'USAGE', KEYS[1])", Long::class.java)
        }
    }
