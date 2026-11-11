package com.delightroom.mission.app.worker.deadline

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.port.GroupDayRepository
import com.delightroom.mission.core.service.DeadlineService
import com.delightroom.mission.infra.redis.RedisInfraAutoConfiguration
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisCallback
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * `worker: deadline` 테스트 준비 — 그날 그룹 Hash · 명단 Hash · `day:{<b>}:<date>` 를 LLD game-session §2.2 칸 그대로 직접 쓴다
 * (명단을 만드는 game-session 구현 없이). 날짜는 테스트마다 먼 미래의 다른 날이라 같은 Redis 를 쓰는 다른 테스트와 겹치지 않는다.
 *
 * 「지금」은 `game.clock-offset` 으로 옮긴다 — [at] 은 Redis `TIME` 을 기준으로 오프셋을 잡아 Lua 의 `t` 가 원하는 시각이 되게 한다.
 */
class DeadlineFixture(val redis: ReactiveStringRedisTemplate) {
    val date: LocalDate = LocalDate.of(2031, 1, 1).plusDays(Random.nextLong(0, 20_000))
    private val base = GameProperties()
    val keys = RedisKeys(base)
    val scripts = RedisScripts(redis)

    /** 그룹원 한 줄. `null` 칸은 쓰지 않는다. */
    data class M(
        val uid: Long,
        val lid: String,
        val st: String,
        val p: Int,
        val cur: String? = null,
        val ls: Long? = null,
    )

    /** Redis `TIME`(µs). */
    suspend fun redisNowUs(): Long =
        redis.execute(ReactiveRedisCallback { it.serverCommands().time(TimeUnit.MICROSECONDS) }).awaitSingle()

    /** Redis `TIME` 에 맞춘 시계 — 스케줄러가 띄우는 시각과 Lua 의 `t` 가 같은 기준이 되게(호스트 · 컨테이너 시계 차이를 없앤다). */
    suspend fun redisClock(): Clock {
        val host = Clock.systemUTC()
        val hostUs = micros(host.instant())
        return Clock.offset(host, Duration.ofNanos((redisNowUs() - hostUs) * 1_000L))
    }

    /** Lua 의 `t` 가 지금 [date] 08:00:00 + [sinceDeadline] 이 되는 설정. */
    suspend fun at(sinceDeadline: Duration, submitGrace: Duration = base.submitGrace): GameProperties {
        val target = base.deadlineAt(date).plus(sinceDeadline)
        val targetUs = target.epochSecond * 1_000_000L + target.nano / 1_000L
        return GameProperties(submitGrace = submitGrace, clockOffset = Duration.ofNanos((targetUs - redisNowUs()) * 1_000L))
    }

    fun repository(game: GameProperties): GroupDayRepository =
        RedisInfraAutoConfiguration().groupDayRepository(redis, RedisScripts(redis), RedisKeys(game), game)

    fun service(game: GameProperties, options: DeadlineService.Options = DeadlineService.Options()) =
        DeadlineService(repository(game), game, options)

    /** [date] KST [time] 의 µs. */
    fun us(time: LocalTime): Long = micros(date.atTime(time).atZone(base.zone).toInstant())

    fun micros(at: Instant): Long = at.epochSecond * 1_000_000L + at.nano / 1_000L

    /** 그룹들을 한꺼번에 쓴다 — 명단(`#size` · `#name` · uid → 로그인 아이디) · 그룹 Hash(2명 이상만) · `day:` Set. */
    suspend fun write(groups: Map<GroupId, List<M>>, extra: (GroupId) -> Map<String, String> = { emptyMap() }) = coroutineScope {
        val gate = Semaphore(256)
        groups.forEach { (gid, members) ->
            launch { gate.withPermit { writeOne(gid, members, extra(gid)) } }
        }
    }

    private suspend fun writeOne(gid: GroupId, members: List<M>, extra: Map<String, String>) {
        val roster = linkedMapOf("#size" to members.size.toString(), "#name" to "g${gid.value}")
        members.forEach { roster[it.uid.toString()] = it.lid }
        redis.opsForHash<String, String>().putAll(keys.members(gid, date), roster).awaitSingle()
        if (members.size >= 2) {
            val fields = linkedMapOf("date" to date.toString(), "gid" to gid.value.toString(), "size" to members.size.toString(), "version" to "0")
            members.forEach { m ->
                fields["${m.uid}:lid"] = m.lid
                fields["${m.uid}:st"] = m.st
                fields["${m.uid}:p"] = m.p.toString()
                if (m.st != "NOT_STARTED") {
                    fields["${m.uid}:sa"] = "1"
                    fields["${m.uid}:late"] = "0"
                }
                m.cur?.let { fields["${m.uid}:cur"] = it }
                m.ls?.let { fields["${m.uid}:ls"] = it.toString() }
            }
            fields += extra
            redis.opsForHash<String, String>().putAll(keys.group(gid, date), fields).awaitSingle()
        }
        redis.opsForSet().add(keys.dayGroups(keys.bucket(gid), date), gid.value.toString()).awaitSingle()
    }

    suspend fun hash(gid: GroupId): Map<String, String> =
        redis.opsForHash<String, String>().entries(keys.group(gid, date)).collectList().awaitSingle().associate { it.key to it.value }

    /** 이 날짜의 버킷 Stream 이벤트 전부(gid → 이벤트 목록, Stream 순서). */
    suspend fun events(): Map<Long, List<Map<String, String>>> = coroutineScope {
        val ops = redis.opsForStream<String, String>()
        (0 until base.bucketCount)
            .map { b -> async { ops.range(keys.events(b), Range.unbounded()).collectList().awaitSingle() } }
            .flatMap { it.await() }
            .map { it.value }
            .filter { it["date"] == date.toString() }
            .groupBy { requireNotNull(it["gid"]).toLong() }
    }

    /** Lua `solve`(solve-signal) — 유예 풀이. 반환 코드만. */
    suspend fun solve(game: GameProperties, gid: GroupId, uid: Long, no: Int, qid: String, clickedAt: Long): String {
        val reply = scripts.eval(
            "solve",
            listOf(keys.group(gid, date), keys.members(gid, date), keys.events(gid), keys.channel(gid)),
            listOf(
                uid.toString(), gid.value.toString(), date.toString(),
                us(game.startTime).toString(), micros(game.deadlineAt(date)).toString(), micros(game.graceEndAt(date)).toString(),
                game.clockOffsetUs.toString(), no.toString(), qid, clickedAt.toString(), "${date}T07:59:59+09:00",
            ),
        )
        return reply[0] as String
    }

    companion object {
        /** 1차에 확정되는 그룹(S1 꼴) — `a` 진행 중 1(2번 문제 보는 중) · `b` 시작 안 함 · `c` 성공. */
        fun decided(uid0: Long, lsBase: Long) = listOf(
            M(uid0, "a$uid0", "IN_PROGRESS", 1, cur = "q2", ls = lsBase),
            M(uid0 + 1, "b$uid0", "NOT_STARTED", -1),
            M(uid0 + 2, "c$uid0", "SUCCEEDED", 3, ls = lsBase + 1_000_000L),
        )

        /** 1차에 미루는 그룹(S3 꼴) — `a` 진행 중 2 · 3번 문제 보는 중 · `b` 성공. */
        fun pending(uid0: Long, lsBase: Long) = listOf(
            M(uid0, "a$uid0", "IN_PROGRESS", 2, cur = "q3", ls = lsBase),
            M(uid0 + 1, "b$uid0", "SUCCEEDED", 3, ls = lsBase + 1_000_000L),
        )

        /** 07:40 에 전원 성공으로 확정된 그룹(S12 꼴) — Lua `solve` 가 쓴 칸. */
        fun early(uid0: Long, lsBase: Long) = listOf(
            M(uid0, "a$uid0", "SUCCEEDED", 3, ls = lsBase),
            M(uid0 + 1, "b$uid0", "SUCCEEDED", 3, ls = lsBase + 1_000_000L),
        )

        val EARLY_FIELDS = mapOf("result" to "SUCCEEDED", "decidedAt" to "1", "ranking" to "[]", "rankingFinal" to "1")
    }
}
