package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.roster.GroupRestore
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RosterRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import reactor.core.publisher.Flux
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration
import java.time.LocalDate

/** 그날 명단(LLD game-session §2.2 명단 Hash · §4.1 `roster.lua`). */
internal class RedisRosterRepository(
    private val redis: ReactiveStringRedisTemplate,
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
    private val gameClock: GameClock,
) : RosterRepository {
    override suspend fun find(gid: GroupId, date: LocalDate): DayRoster? {
        val fields = redis.opsForHash<String, String>().entries(keys.members(gid, date))
            .collectMap({ it.key }, { it.value })
            .awaitSingle()
        if (SIZE !in fields) return null
        val members = fields.filterKeys { !it.startsWith('#') }
            .entries.associate { (uid, lid) -> UserId(uid.toLong()) to LoginId(lid) }
        return DayRoster(gid, date, GroupName(fields.getValue(NAME)), members)
    }

    override suspend fun createIfAbsent(roster: DayRoster, restore: GroupRestore?): Boolean {
        val b = keys.bucket(roster.gid)
        val args = buildList {
            add(roster.date.toString())
            add(roster.gid.value.toString())
            add(roster.name.value)
            add(ttlMs(roster.date).toString())
            add(roster.size.toString())
            roster.members.forEach { (uid, lid) ->
                add(uid.value.toString())
                add(lid.value)
            }
            // 재적재 꼬리(LLD platform §4.1) — 그룹 Hash 를 만들 때만 쓰인다
            if (restore != null) {
                add(restore.version.toString())
                add(if (restore.rerank) "1" else "0")
                add(restore.fields.size.toString())
                restore.fields.forEach { (field, value) ->
                    add(field)
                    add(value)
                }
            }
        }
        val reply = scripts.eval(
            "roster",
            listOf(keys.members(roster.gid, roster.date), keys.group(roster.gid, roster.date), keys.dayGroups(b, roster.date)),
            args,
        )
        check(reply.size == 2 && reply[0] == "OK") { "roster.lua 응답이 이상하다: $reply" }
        return reply[1] == 1L
    }

    /** 버킷마다 `SET rk:{<b>}:<date> 1 PX <남은 ms>` — 버킷 키라 Cluster 에서도 각자의 슬롯에 놓인다(한 명령에 묶지 않는다). */
    override suspend fun markConfirmed(date: LocalDate) {
        val ttl = Duration.ofMillis(ttlMs(date))
        Flux.range(0, keys.bucketCount)
            .flatMap({ b -> redis.opsForValue().set(keys.rehydrateMark(b, date), "1", ttl) }, MARK_CONCURRENCY)
            .collectList()
            .awaitSingle()
    }

    override suspend fun countUnmarked(date: LocalDate): Int =
        Flux.range(0, keys.bucketCount)
            .flatMap({ b -> redis.hasKey(keys.rehydrateMark(b, date)) }, MARK_CONCURRENCY)
            .asFlow().toList()
            .count { !it }

    /** 다음 날 08:00 까지 남은 ms(LLD §4.1 ARGV[4]) — 앱 게임 시각 기준이라 `game.clock-offset` · 테스트 시계와 맞는다. */
    private fun ttlMs(date: LocalDate): Long {
        val ttl = Duration.between(gameClock.now(), gameClock.rosterExpireAt(date)).toMillis()
        check(ttl > 0) { "명단 만료가 이미 지났다: date=$date" }
        return ttl
    }

    private companion object {
        const val SIZE = "#size"
        const val NAME = "#name"

        /** 표시 1,024개를 쓰고 셀 때 동시에 보내는 명령 수 — 한 연결 위에서 파이프라인으로 나간다. */
        const val MARK_CONCURRENCY = 64
    }
}
