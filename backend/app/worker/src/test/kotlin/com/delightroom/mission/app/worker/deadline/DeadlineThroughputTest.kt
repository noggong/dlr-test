package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlinePhase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.ContextConfiguration
import java.time.Duration
import java.time.LocalTime
import kotlin.random.Random

/** ORACLE deadline-result O17 — Redis 컨테이너 하나 · 그룹 10,000개 × 그룹원 5명 · 기본 설정으로 1차가 5초 안에 끝난다. */
@SpringBootTest(properties = ["worker.persister.enabled=false", "worker.deadline.enabled=false"])
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
class DeadlineThroughputTest
    @Autowired
    constructor(
        private val redis: ReactiveStringRedisTemplate,
    ) {
        @Test
        fun `O17 그룹 1만 개의 1차가 5초 안에 실패 없이 끝난다`(): Unit = runBlocking {
            val f = DeadlineFixture(redis)
            val base = 1024L * Random.nextLong(1_000, 1_000_000_000)
            val ls = f.us(LocalTime.of(7, 30))
            val groups = (0 until 10_000).associate { i ->
                val gid = GroupId(base + i)
                val uid0 = gid.value * 10
                gid to DeadlineFixture.decided(uid0, ls) + listOf(
                    DeadlineFixture.M(uid0 + 3, "d$uid0", "SUCCEEDED", 3, ls = ls + 2_000_000L),
                    DeadlineFixture.M(uid0 + 4, "e$uid0", "IN_PROGRESS", 0),
                )
            }
            f.write(groups)
            val service = f.service(f.at(Duration.ofMillis(500)))

            val report = service.runPhase(f.date, DeadlinePhase.FIRST)

            println("O17 elapsed=${report.elapsed.toMillis()}ms counts=${report.counts}")
            assertThat(report.failedGroups).isEmpty()
            assertThat(report.failedBuckets).isEmpty()
            assertThat(report.buckets).isEqualTo(1024)
            assertThat(report.groups).isEqualTo(10_000)
            // 1차 창 안에 끝나면 모두 DECIDED, 넘겼으면 남은 것은 DECIDED_FINAL — 어느 쪽이든 결과는 한 번 쓰였다
            assertThat(report.count(DeadlineCode.DECIDED) + report.count(DeadlineCode.DECIDED_FINAL)).isEqualTo(10_000)
            assertThat(report.elapsed).isLessThanOrEqualTo(Duration.ofSeconds(5))
        }
    }
