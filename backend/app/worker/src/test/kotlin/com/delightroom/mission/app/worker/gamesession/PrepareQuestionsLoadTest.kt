package com.delightroom.mission.app.worker.gamesession

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.prepare.PrepareQuestionsJob
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.core.port.QuestionRepository
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import java.time.LocalTime

/**
 * `worker: prepare-questions` 처리량(ORACLE game-session O33 — D18 · HLD §5 · LLD §3.6). 유저 100,000명(그룹 10,000개 × 10명) ·
 * 유저당 출제 이력 30개를 넣고 06:30 실행([PrepareQuestionsJob.run] `(D, 0)`)을 그대로 돌려 요약의 초당 유저를 본다.
 * 묶음 크기는 운영 기본값(1,000). 06:30 실행은 `joined_at < D 06:30` 전부라 같은 JVM 의 다른 테스트가 넣은 유저도 대상에 든다 —
 * 판정은 요약 전체 수치로 하고, 이 테스트의 유저는 모두 키가 생겼는지를 따로 본다. `D` 는 KST 오늘 + 3일(다른 테스트의 날짜와 겹치지 않게).
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
class PrepareQuestionsLoadTest
    @Autowired
    constructor(
        private val job: PrepareQuestionsJob,
        private val cache: QuestionBankCache,
        private val questions: QuestionRepository,
        db: DatabaseClient,
        connectionFactory: ConnectionFactory,
    ) {
        private val fixture = LoadFixture(db, connectionFactory)
        private val d: LocalDate = LocalDate.now(LoadFixture.KST).plusDays(3)

        @Test
        fun `O33 유저 100000명 출제 이력 30개 06시30분 실행 초당 유저 2100 이상`(): Unit = runBlocking {
            fixture.schema()
            val prefix = LoadFixture.prefix("L33")
            val joinedAt = d.minusDays(1).atTime(LocalTime.of(6, 0)).atZone(LoadFixture.KST).toOffsetDateTime()
            val seeded = System.nanoTime()
            val groups = fixture.groups(prefix, GROUPS, GROUP_SIZE, joinedAt)
            fixture.serve(prefix, SERVED_PER_USER)
            val users = groups.values.flatten()
            assertThat(users).hasSize(GROUPS * GROUP_SIZE)
            println("[O33] seeded users=${users.size} served=${users.size * SERVED_PER_USER} in ${(System.nanoTime() - seeded) / 1_000_000}ms")

            val summary = job.run(d, 0)

            println(
                "[O33] targets=${summary.targets} created=${summary.created} existing=${summary.existing} " +
                    "exhausted=${summary.exhausted} failedBatches=${summary.failedBatches} " +
                    "elapsedMs=${summary.elapsed.toMillis()} usersPerSec=${summary.usersPerSecond}",
            )
            assertThat(summary.failedBatches).isZero()
            assertThat(summary.targets).isGreaterThanOrEqualTo(users.size)
            assertThat(summary.created).isGreaterThanOrEqualTo(users.size)
            // 이 테스트의 유저는 모두 은행이 생겼다(고갈 없음 — 난이도마다 120개 중 이력은 10개 안팎) · 은행에 이력 없음(I5 와 같은 검사, 표본)
            val sample = users.shuffled().take(SAMPLE)
            val served = questions.findServedIds(sample)
            for (uid in sample) {
                val bank = checkNotNull(cache.get(uid, d)) { "은행 없음 ${uid.value}" }
                assertThat(bank.idsByDifficulty.values.flatten().toSet() intersect served[uid].orEmpty())
                    .describedAs("은행 ∩ 출제 이력 ${uid.value}").isEmpty()
            }
            assertThat(summary.usersPerSecond).describedAs("초당 유저").isGreaterThanOrEqualTo(2_100)
        }

        private companion object {
            const val GROUPS = 10_000
            const val GROUP_SIZE = 10
            const val SERVED_PER_USER = 30
            const val SAMPLE = 1_000
        }
    }
