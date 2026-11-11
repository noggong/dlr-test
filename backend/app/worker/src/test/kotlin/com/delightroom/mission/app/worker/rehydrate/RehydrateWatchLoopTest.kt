package com.delightroom.mission.app.worker.rehydrate

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.member.MemberJob
import com.delightroom.mission.app.worker.persister.eventually
import com.delightroom.mission.infra.redis.RedisKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * `worker: member` 의 감시 루프 자체(ORACLE platform O30 — LLD platform §4.2 발동 2). O25 는 [MemberJob.watchOnce] 를 직접 부르고,
 * 여기서는 스프링이 띄운 루프(`worker.member.enabled=true` · `watch-interval=50ms`)가 표시 사라짐을 스스로 찾는지 본다.
 * 시각은 옮길 수 있는 [Clock] 빈([ShiftClock])으로 — 처음 `D 07:30`(기동 때 그날 실행), 다음에 `D+1 07:00:00.5` 로 넘긴다.
 * 루프가 무엇을 했는지는 로그(`member.done` · `member.redis_lost`)로 읽는다.
 */
@SpringBootTest(
    properties = [
        "spring.sql.init.mode=always",
        "worker.member.watch-interval=50ms",
        "worker.member.retry-backoff=50ms",
        "worker.persister.enabled=false",
        "worker.deadline.enabled=false",
        "worker.prepare-questions.enabled=false",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
@Import(RehydrateWatchLoopTest.Shift::class)
@ExtendWith(OutputCaptureExtension::class)
@DirtiesContext
class RehydrateWatchLoopTest
    @Autowired
    constructor(
        private val job: MemberJob,
        private val clock: ShiftClock,
        private val keys: RedisKeys,
        redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
    ) {
        /** 여러 스레드(루프 · 테스트)가 읽는 고정 시각 — [set] 으로만 옮긴다. */
        class ShiftClock(@Volatile private var now: Instant, private val zone: ZoneId) : Clock() {
            override fun getZone(): ZoneId = zone

            override fun withZone(zone: ZoneId): Clock = ShiftClock(now, zone)

            override fun instant(): Instant = now

            fun set(at: Instant) {
                now = at
            }
        }

        @TestConfiguration
        class Shift {
            @Bean
            @Primary
            fun shiftClock(): ShiftClock = ShiftClock(at(D, LocalTime.of(7, 30)), RehydrateFixture.ZONE)
        }

        private val fx = RehydrateFixture(db, redis, keys, D)

        @Test
        fun `O30 감시 루프가 표시 사라짐을 스스로 찾아 다시 올리고 다음 07시 뒤에는 그 날짜를 보지 않는다`(output: CapturedOutput): Unit = runBlocking {
            assertThat(job.isRunning).isTrue()
            val next = D.plusDays(1)
            // 기동 때 07:00 ~ 08:00 이라 그날 실행을 바로 한다 — 끝나면 그 날짜 표시를 본 것이다
            eventually(what = "기동 실행 member.done date=$D") { "member.done date=$D " in output.out }

            // 그 뒤 생긴 그룹 — 명단은 아직 없고 PostgreSQL 에만 진행 기록이 있다(다른 인스턴스 · 앞 Redis 에서 진행한 것처럼)
            val g = fx.group(3)
            val (u1, u2, u3) = g.uids
            row(g, u1, 1, "IN_PROGRESS", "07:10:00", "07:20:00.000001")
            row(g, u2, 0, "IN_PROGRESS", "07:11:00", null)
            assertThat(fx.group(g)).isEmpty()

            fx.flushAll()
            eventually(what = "감시가 표시 사라짐을 찾아 다시 올림") { fx.group(g).isNotEmpty() && "member.done date=$D " in output.out.substringAfterLast(LOST + D, "") }
            assertThat(fx.group(g)).containsEntry("$u1:p", "1").containsEntry("$u1:st", "IN_PROGRESS").containsKey("$u1:ls")
                .containsEntry("$u2:p", "0").containsEntry("$u3:p", "-1")
            assertThat(fx.isDayMember(g)).isTrue()
            assertThat(lost(output, D)).isEqualTo(1)

            // 다음 07:00 이 지나면 그날(D+1) 실행을 하고, 감시 대상도 D+1 로 넘어간다
            clock.set(at(next, LocalTime.of(7, 0, 0, 500_000_000)))
            eventually(what = "다음 날 실행 member.done date=$next") { "member.done date=$next " in output.out }
            assertThat(fx.hash(keys.group(g.gid, next))).containsEntry("version", "0").containsEntry("$u1:p", "-1")

            fx.flushAll()
            eventually(what = "감시가 D+1 을 다시 올림") { fx.hash(keys.group(g.gid, next)).isNotEmpty() }
            assertThat(lost(output, next)).isEqualTo(1)
            // 감시 주기 여러 번을 지나도 D 는 다시 올리지 않는다
            delay(500)
            assertThat(fx.group(g)).isEmpty()
            assertThat(fx.roster(g)).isEmpty()
            assertThat(lost(output, D)).isEqualTo(1)
            // 다른 캐시 컨텍스트의 루프 로그가 섞이지 않게 이 날짜들만 본다
            assertThat(output.out.lines().filter { "member.failed" in it && ("date=$D" in it || "date=$next" in it) }).isEmpty()
        }

        /** 감시가 [day] 의 표시 사라짐을 찾은 횟수(실행 도중 사라짐 로그는 세지 않는다). */
        private fun lost(output: CapturedOutput, day: LocalDate): Int =
            output.out.lines().count { "$LOST$day " in it && "그날 명단" in it }

        private suspend fun row(g: TestGroup, uid: Long, p: Int, st: String, startedAt: String, lastSolvedAt: String?) {
            val ls = lastSolvedAt?.let { "'${D}T$it+09:00'" } ?: "NULL"
            db.sql(
                "INSERT INTO daily_progress (game_date, user_id, group_id, progress, status, started_at, is_late, last_solved_at) " +
                    "VALUES ('$D', $uid, ${g.gid.value}, $p, '$st', '${D}T$startedAt+09:00', true, $ls)",
            ).then().awaitFirstOrNull()
        }

        companion object {
            val D: LocalDate = LocalDate.now(RehydrateFixture.ZONE).plusDays(21)

            const val LOST = "member.redis_lost date="

            fun at(day: LocalDate, time: LocalTime): Instant = day.atTime(time).atZone(RehydrateFixture.ZONE).toInstant()
        }
    }
