package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.roster.DayRoster
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.RosterRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
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

    override suspend fun createIfAbsent(roster: DayRoster): Boolean {
        val b = keys.bucket(roster.gid)
        val args = buildList {
            add(roster.date.toString())
            add(roster.gid.value.toString())
            add(roster.name.value)
            add(gameClock.rosterExpireAt(roster.date).epochSecond.toString())
            add(roster.size.toString())
            roster.members.forEach { (uid, lid) ->
                add(uid.value.toString())
                add(lid.value)
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

    private companion object {
        const val SIZE = "#size"
        const val NAME = "#name"
    }
}
