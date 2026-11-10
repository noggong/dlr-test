package com.delightroom.mission.infra.redis.platform

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.infra.redis.RedisKeys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** ORACLE platform O11 — 키 이름이 HLD platform §4.1 표와 글자 그대로 같다. */
class RedisKeysTest {
    private val keys = RedisKeys(GameProperties())
    private val date = LocalDate.of(2026, 11, 11)
    private val gid = GroupId(1025)
    private val uid = UserId(7)

    @Test
    fun `O11 버킷은 gid mod 1024`() {
        assertThat(keys.bucketCount).isEqualTo(1024)
        assertThat(keys.bucket(GroupId(1025))).isEqualTo(1)
        assertThat(keys.bucket(GroupId(1024))).isEqualTo(0)
        assertThat(keys.bucket(GroupId(1023))).isEqualTo(1023)
    }

    @Test
    fun `O11 키 이름이 HLD 4_1 표와 같다`() {
        assertThat(keys.group(gid, date)).isEqualTo("g:{1}:1025:2026-11-11")
        assertThat(keys.members(gid, date)).isEqualTo("g:{1}:1025:2026-11-11:members")
        assertThat(keys.dayGroups(1, date)).isEqualTo("day:{1}:2026-11-11")
        assertThat(keys.events(1)).isEqualTo("ev:{1}")
        assertThat(keys.events(gid)).isEqualTo("ev:{1}")
        assertThat(keys.channel(gid)).isEqualTo("ch:{1}:1025")
        assertThat(keys.ticket("t")).isEqualTo("tk:{t}")
        assertThat(keys.session(uid)).isEqualTo("sess:{7}")
        assertThat(keys.result(gid)).isEqualTo("result:{1025}")
        assertThat(keys.bank(uid, date)).isEqualTo("bank:{7}:2026-11-11")
    }

    @Test
    fun `O11 한 그룹의 Lua 키 4개는 해시태그가 같다`() {
        val tags = listOf(keys.group(gid, date), keys.members(gid, date), keys.events(gid), keys.channel(gid))
            .map { it.substringAfter('{').substringBefore('}') }

        assertThat(tags).containsOnly("1")
    }

    @Test
    fun `O11 버킷 범위 밖은 거절`() {
        assertThatThrownBy { keys.events(1024) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { keys.dayGroups(-1, date) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
