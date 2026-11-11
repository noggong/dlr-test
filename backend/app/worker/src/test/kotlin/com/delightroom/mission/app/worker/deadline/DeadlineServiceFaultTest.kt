package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.DeadlineCode
import com.delightroom.mission.core.domain.result.DeadlineOutcome
import com.delightroom.mission.core.domain.result.DeadlinePhase
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.service.DeadlineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** `DeadlineService` 의 오류 경로(LLD deadline-result §3.2 5) — 가짜 저장소로 SSCAN 실패 · 그룹 오류 · `EARLY` 초과 · 중복 · 취소를 본다. */
class DeadlineServiceFaultTest {
    private val game = GameProperties(bucketCount = 4)
    private val date = LocalDate.of(2031, 1, 10)
    private val options = DeadlineService.Options(retryBackoff = Duration.ofMillis(1), earlyRetryDelay = Duration.ofMillis(1), earlyRetryMax = 3)

    /** 버킷 b 의 gid = b, b+4, b+8 (3개씩 · 12개). [scan] · [run] 으로 오류를 끼운다. */
    private open class Fake : GroupDayRepository {
        val calls = ConcurrentHashMap<Long, AtomicInteger>()
        val scans = ConcurrentHashMap<Int, AtomicInteger>()

        open fun gids(bucket: Int): List<Long> = listOf(bucket.toLong(), bucket + 4L, bucket + 8L)

        open suspend fun scanFault(bucket: Int, scan: Int, index: Int) {}

        open fun code(gid: Long, call: Int): DeadlineCode = DeadlineCode.DECIDED

        override suspend fun runDeadline(gid: GroupId, date: LocalDate, phase: DeadlinePhase): DeadlineOutcome {
            val call = calls.computeIfAbsent(gid.value) { AtomicInteger() }.incrementAndGet()
            return DeadlineOutcome(code(gid.value, call), 1)
        }

        override fun scanDayGroups(bucket: Int, date: LocalDate): Flow<GroupId> = flow {
            val scan = scans.computeIfAbsent(bucket) { AtomicInteger() }.incrementAndGet()
            gids(bucket).forEachIndexed { i, gid ->
                scanFault(bucket, scan, i)
                emit(GroupId(gid))
            }
        }
    }

    @Test
    fun `SSCAN 이 도중에 실패하면 버킷을 다시 훑고 이미 띄운 그룹은 두 번 세지 않는다`(): Unit = runBlocking {
        val repo = object : Fake() {
            override suspend fun scanFault(bucket: Int, scan: Int, index: Int) {
                if (bucket == 1 && scan == 1 && index == 2) error("연결 끊김")
            }
        }
        val report = DeadlineService(repo, game, options).runPhase(date, DeadlinePhase.FIRST)

        assertThat(repo.scans[1]!!.get()).isEqualTo(2)
        assertThat(report.counts).isEqualTo(mapOf(DeadlineCode.DECIDED to 12))
        assertThat(repo.calls.values.map { it.get() }.toSet()).containsExactly(1)
        assertThat(report.failedBuckets).isEmpty()
    }

    @Test
    fun `SSCAN 이 계속 실패하면 retry 번 다시 훑고 그 버킷만 failedBuckets 다`(): Unit = runBlocking {
        val repo = object : Fake() {
            override suspend fun scanFault(bucket: Int, scan: Int, index: Int) {
                if (bucket == 2 && index == 1) error("연결 끊김")
            }
        }
        val report = DeadlineService(repo, game, options).runPhase(date, DeadlinePhase.FIRST)

        assertThat(repo.scans[2]!!.get()).isEqualTo(1 + options.retry)
        assertThat(report.failedBuckets).containsExactly(2)
        // 버킷 2 의 첫 gid 는 처리됐고 나머지 둘은 못 했다
        assertThat(report.count(DeadlineCode.DECIDED)).isEqualTo(10)
    }

    @Test
    fun `오류 난 그룹은 백오프로 다시 하고 retry 를 넘기면 failedGroups 에 마지막 오류와 함께 남는다`(): Unit = runBlocking {
        val repo = object : Fake() {
            override fun code(gid: Long, call: Int): DeadlineCode = when {
                gid == 5L && call <= 2 -> throw IllegalStateException("타임아웃")
                gid == 6L -> throw IllegalStateException("스크립트 오류")
                else -> DeadlineCode.DECIDED
            }
        }
        val report = DeadlineService(repo, game, options).runPhase(date, DeadlinePhase.FIRST)

        assertThat(repo.calls[5]!!.get()).isEqualTo(3)
        assertThat(repo.calls[6]!!.get()).isEqualTo(1 + options.retry)
        assertThat(report.failedGroups.map { it.gid.value }).containsExactly(6L)
        assertThat(report.failedGroups.single().reason).contains("스크립트 오류")
        assertThat(report.count(DeadlineCode.DECIDED)).isEqualTo(11)
        assertThat(report.groups).isEqualTo(12)
    }

    @Test
    fun `EARLY 를 earlyRetryMax 번 넘게 받으면 실패로 센다`(): Unit = runBlocking {
        val repo = object : Fake() {
            override fun code(gid: Long, call: Int) = if (gid == 3L) DeadlineCode.EARLY else DeadlineCode.PENDING
        }
        val report = DeadlineService(repo, game, options).runPhase(date, DeadlinePhase.SECOND)

        assertThat(repo.calls[3]!!.get()).isEqualTo(1 + options.earlyRetryMax)
        assertThat(report.failedGroups.map { it.gid.value to it.reason }).containsExactly(3L to "EARLY")
        assertThat(report.counts).isEqualTo(mapOf(DeadlineCode.PENDING to 11))
    }

    @Test
    fun `SSCAN 이 같은 gid 를 두 번 주어도 한 번만 부르고 센다`(): Unit = runBlocking {
        val repo = object : Fake() {
            override fun gids(bucket: Int) = super.gids(bucket).let { it + it }
        }
        val report = DeadlineService(repo, game, options).runPhase(date, DeadlinePhase.FIRST)

        assertThat(report.groups).isEqualTo(12)
        assertThat(repo.calls.values.map { it.get() }.toSet()).containsExactly(1)
    }

    @Test
    fun `취소하면 단계가 곧바로 끝난다`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val repo = object : GroupDayRepository {
            override suspend fun runDeadline(gid: GroupId, date: LocalDate, phase: DeadlinePhase): DeadlineOutcome {
                entered.complete(Unit)
                awaitCancellation()
            }

            override fun scanDayGroups(bucket: Int, date: LocalDate): Flow<GroupId> = flow { emit(GroupId(bucket.toLong())) }
        }
        val run = async { DeadlineService(repo, game, options).runPhase(date, DeadlinePhase.FIRST) }
        entered.await()
        withTimeout(2_000) { run.cancelAndJoin() }
        assertThat(run.isCancelled).isTrue()
        assertThat(runCatching { run.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}
