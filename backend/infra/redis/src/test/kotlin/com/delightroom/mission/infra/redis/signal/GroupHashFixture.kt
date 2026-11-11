package com.delightroom.mission.infra.redis.signal

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.LocalDate

/**
 * 그룹 Hash · 명단 Hash 를 LLD solve-signal §2.1 칸 그대로 직접 쓴다 — game-session 구현 없이 Lua 를 돌리기 위한 준비.
 * [Member] 한 줄이 그룹원 칸(`<uid>:lid` · `:st` · `:p` · `:cur` · `:ls` · `:late` · `:sa` · `:pn`)이 된다.
 */
class GroupHashFixture(
    private val redis: ReactiveStringRedisTemplate,
    val groupKey: String,
    val membersKey: String,
    val gid: Long,
    val date: LocalDate,
) {
    data class Member(
        val uid: Long,
        val lid: String,
        val st: String = "IN_PROGRESS",
        val p: Int = 0,
        val cur: String? = null,
        val ls: Long? = null,
        val late: Boolean = false,
        val pn: Int? = null,
    )

    /** 명단(`#size` = 그룹원 수)과 그룹 Hash(2명 이상일 때만 — game-session `roster` 와 같다)를 새로 쓴다. [group] 은 그룹 칸 덧붙임. */
    fun write(members: List<Member>, group: Map<String, String> = emptyMap()) = runBlocking {
        redis.delete(groupKey, membersKey).awaitSingle()
        val roster = mapOf("#size" to members.size.toString(), "#name" to "g$gid") + members.associate { it.uid.toString() to it.lid }
        redis.opsForHash<String, String>().putAll(membersKey, roster).awaitSingle()
        if (members.size < 2) return@runBlocking
        val fields = mutableMapOf("date" to date.toString(), "gid" to gid.toString(), "size" to members.size.toString(), "version" to "0")
        members.forEach { m ->
            val u = m.uid
            fields["$u:lid"] = m.lid
            fields["$u:st"] = m.st
            fields["$u:p"] = m.p.toString()
            if (m.st != "NOT_STARTED") {
                fields["$u:sa"] = "1"
                fields["$u:late"] = if (m.late) "1" else "0"
            }
            m.cur?.let { fields["$u:cur"] = it }
            m.ls?.let { fields["$u:ls"] = it.toString() }
            m.pn?.let { fields["$u:pn"] = it.toString() }
        }
        fields.putAll(group)
        redis.opsForHash<String, String>().putAll(groupKey, fields).awaitSingle()
    }

    /** 명단만 지우거나 원하는 칸으로 다시 쓴다(NO_ROSTER · SOLO_GROUP 준비). */
    fun writeRosterOnly(roster: Map<String, String>?) = runBlocking {
        redis.delete(groupKey, membersKey).awaitSingle()
        if (roster != null) redis.opsForHash<String, String>().putAll(membersKey, roster).awaitSingle()
    }
}
