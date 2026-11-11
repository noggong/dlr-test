package com.delightroom.mission.infra.redis.realtimeboard

import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.BoardRead
import com.delightroom.mission.core.domain.board.BoardReadMode
import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.testfixture.BoardFixtures
import com.delightroom.mission.core.testfixture.BoardFixtures.DATE
import com.delightroom.mission.core.testfixture.BoardFixtures.DOHYUN
import com.delightroom.mission.core.testfixture.BoardFixtures.HANA
import com.delightroom.mission.core.testfixture.BoardFixtures.JUNHO
import com.delightroom.mission.core.testfixture.BoardFixtures.MINJI
import com.delightroom.mission.infra.redis.RedisGroupBoardRepository
import com.delightroom.mission.infra.redis.RedisKeys
import com.delightroom.mission.infra.redis.RedisScripts
import com.delightroom.mission.infra.redis.RedisTestContainer
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.core.ReactiveRedisCallback
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.random.Random

/**
 * ORACLE realtime-board O3 ~ O7 · O10 · O11 — Lua `board_read` 의 판정 순서 7단계(LLD §3)와 [RedisGroupBoardRepository] 의 모드별 창.
 * 창 경계는 실행 순간의 Redis `TIME` 기준으로 ARGV 2 · 3 을 넣어 만든다(ORACLE 테스트 규약). 픽스처는 SPEC §3 의 4명 그룹.
 */
class BoardReadLuaTest {
    private val redis = RedisTestContainer.template
    private val scripts = RedisScripts(redis)
    private val keys = RedisKeys(GameProperties())
    private val gid = GroupId(Random.nextLong(1_000_000L, 1_000_000_000_000L))
    private val group = keys.group(gid, DATE)
    private val roster = keys.members(gid, DATE)

    @AfterEach
    fun cleanUp(): Unit = runBlocking {
        redis.delete(group, roster).awaitSingle()
        Unit
    }

    // ---- 준비 ----

    private fun hset(key: String, fields: Map<String, String>) = runBlocking {
        redis.opsForHash<String, String>().putAll(key, fields).awaitSingle()
    }

    /** 명단 Hash — `#size` 와 uid → 로그인 아이디. */
    private fun roster(members: Map<Long, String>, size: Int = members.size) =
        hset(roster, linkedMapOf("#size" to "$size", "#name" to "g$gid") + members.mapKeys { "${it.key}" })

    private fun fourMembers() {
        roster(mapOf(JUNHO to "junho", MINJI to "minji", DOHYUN to "dohyun", HANA to "hana"))
        hset(group, BoardFixtures.inProgressHash())
    }

    private fun redisNowUs(): Long = runBlocking {
        val ms = redis.execute(ReactiveRedisCallback { c: ReactiveRedisConnection -> c.serverCommands().time() }).awaitSingle()
        ms * 1_000L
    }

    /** Lua 를 직접 — 창 [from, to) 를 µs 로. */
    private fun eval(uid: Long, from: Long, to: Long, mode: String = "board"): List<Any> = runBlocking {
        scripts.eval("board_read", listOf(roster, group), listOf("$uid", "$from", "$to", "0", mode, "2"))
    }

    private val wide = 0L to Long.MAX_VALUE
    private fun evalWide(uid: Long, mode: String = "board") = eval(uid, wide.first, wide.second, mode)

    private fun err(code: String) = listOf("ERR", code)

    // ---- O3 · O10 시간 창 ----

    @Test
    fun `O3 조회 창 — 창 끝이 now 면 거부, now + 1s 면 통과, 창 시작이 now + 1s 면 거부`() {
        fourMembers()
        val now = redisNowUs()
        assertThat(eval(MINJI, now - 3_600_000_000L, now)).isEqualTo(err("GAME_NOT_OPEN"))
        assertThat(eval(MINJI, now - 3_600_000_000L, now + 1_000_000L)[0]).isEqualTo("OK")
        assertThat(eval(MINJI, now + 1_000_000L, now + 3_600_000_000L)).isEqualTo(err("GAME_NOT_OPEN"))
    }

    @Test
    fun `창 시작은 포함한다 — 창 시작 = now 면 통과`() {
        fourMembers()
        val now = redisNowUs()
        assertThat(eval(MINJI, now, now + 3_600_000_000L)[0]).isEqualTo("OK")
        assertThat(eval(MINJI, now, now + 3_600_000_000L, "ticket")[0]).isEqualTo("OK")
    }

    @Test
    fun `O10 티켓 창 — 창 끝 now + 1s 는 통과(Hash 없이 now 만), 창 끝 now · 창 시작 now + 1s 는 거부`() {
        roster(mapOf(JUNHO to "junho", MINJI to "minji")) // 그룹 Hash 없음 — ticket 은 읽지 않는다
        val now = redisNowUs()
        val ok = eval(MINJI, now - 3_600_000_000L, now + 1_000_000L, "ticket")
        assertThat(ok).hasSize(2)
        assertThat(ok[0]).isEqualTo("OK")
        assertThat((ok[1] as String).toLong()).isBetween(now, now + 1_000_000L)
        assertThat(eval(MINJI, now - 3_600_000_000L, now, "ticket")).isEqualTo(err("GAME_NOT_OPEN"))
        assertThat(eval(MINJI, now + 1_000_000L, now + 3_600_000_000L, "ticket")).isEqualTo(err("GAME_NOT_OPEN"))
    }

    // ---- O4 ~ O7 · O11 판정 ----

    @Test
    fun `통과하면 now 와 그룹 Hash 전체를 돌려준다`() {
        fourMembers()
        val reply = evalWide(MINJI)
        assertThat(reply[0]).isEqualTo("OK")
        val snapshot = GroupDaySnapshot.parseFlat(DATE, reply.drop(2).map { it as String })
        assertThat(snapshot).isEqualTo(GroupDaySnapshot.parse(DATE, BoardFixtures.inProgressHash()))
    }

    @Test
    fun `O4 · O11 명단에 없는 uid 는 NOT_PARTICIPANT — 조회 · 티켓 둘 다`() {
        fourMembers()
        assertThat(evalWide(999L)).isEqualTo(err("NOT_PARTICIPANT"))
        assertThat(evalWide(999L, "ticket")).isEqualTo(err("NOT_PARTICIPANT"))
    }

    @Test
    fun `O5 · O11 명단 #size = 1 이면 SOLO_GROUP — 조회 · 티켓 둘 다`() {
        roster(mapOf(MINJI to "minji"))
        hset(group, mapOf("version" to "0", "$MINJI:lid" to "minji", "$MINJI:st" to "NOT_STARTED", "$MINJI:p" to "-1"))
        assertThat(evalWide(MINJI)).isEqualTo(err("SOLO_GROUP"))
        assertThat(evalWide(MINJI, "ticket")).isEqualTo(err("SOLO_GROUP"))
    }

    @Test
    fun `O6 명단 Hash 없음 · 그룹 Hash 없음은 GAME_NOT_OPEN — 티켓은 그룹 Hash 를 보지 않는다`() {
        assertThat(evalWide(MINJI)).isEqualTo(err("GAME_NOT_OPEN"))
        assertThat(evalWide(MINJI, "ticket")).isEqualTo(err("GAME_NOT_OPEN"))
        roster(mapOf(JUNHO to "junho", MINJI to "minji"))
        assertThat(evalWide(MINJI)).isEqualTo(err("GAME_NOT_OPEN"))
        assertThat(evalWide(MINJI, "stream")).isEqualTo(err("GAME_NOT_OPEN"))
        assertThat(evalWide(MINJI, "ticket")[0]).isEqualTo("OK")
    }

    @Test
    fun `O7 판정 순서 — 창 밖 + 그룹원 아님 + 1명 그룹은 GAME_NOT_OPEN, 그룹원 아님 + 1명 그룹은 NOT_PARTICIPANT`() {
        roster(mapOf(MINJI to "minji"))
        val now = redisNowUs()
        assertThat(eval(999L, now + 1_000_000L, now + 3_600_000_000L)).isEqualTo(err("GAME_NOT_OPEN"))
        assertThat(evalWide(999L)).isEqualTo(err("NOT_PARTICIPANT"))
    }

    @Test
    fun `board_read 는 키를 쓰지 않는다 — 없으면 만들지 않고, 있으면 바꾸지 않는다`() {
        evalWide(MINJI)
        evalWide(MINJI, "ticket")
        val exists = runBlocking { redis.countExistingKeys(listOf(group, roster)).awaitSingle() }
        assertThat(exists).isZero()

        fourMembers()
        val before = hgetall(group) to hgetall(roster)
        listOf("board", "ticket", "stream").forEach { evalWide(MINJI, it) }
        evalWide(999L)
        assertThat(hgetall(group) to hgetall(roster)).isEqualTo(before)
    }

    private fun hgetall(key: String): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(key).collectList().awaitSingle().associate { it.key to it.value }
    }

    @Test
    fun `최소 그룹원 수는 ARGV 6 — 3 이면 2명 그룹도 SOLO_GROUP`() {
        roster(mapOf(JUNHO to "junho", MINJI to "minji"))
        val reply = runBlocking {
            scripts.eval("board_read", listOf(roster, group), listOf("$MINJI", "0", "${Long.MAX_VALUE}", "0", "board", "3"))
        }
        assertThat(reply).isEqualTo(err("SOLO_GROUP"))
    }

    // ---- 저장소: 모드별 창(game.clock-offset 로 시각을 옮긴다) ----

    /** 지금을 [DATE] 의 [time] KST 로 옮기는 오프셋 설정. */
    private fun propertiesAt(time: LocalTime): GameProperties {
        val target = DATE.atTime(time).atZone(BoardFixtures.ZONE).toInstant()
        val redisNow = Instant.ofEpochSecond(0, redisNowUs() * 1_000L)
        return GameProperties(clockOffset = Duration.between(redisNow, target))
    }

    private fun repoRead(time: LocalTime, mode: BoardReadMode, uid: Long = MINJI): BoardRead = runBlocking {
        val properties = propertiesAt(time)
        RedisGroupBoardRepository(scripts, RedisKeys(properties), properties).read(gid, DATE, UserId(uid), mode)
    }

    @Test
    fun `저장소 BOARD — 07시 30분은 Opened(now · Hash), 06시 59분 59초 · 08시 00분 01초는 GAME_NOT_OPEN`() {
        fourMembers()
        val opened = repoRead(LocalTime.of(7, 30), BoardReadMode.BOARD) as BoardRead.Opened
        val expected = DATE.atTime(7, 30).atZone(BoardFixtures.ZONE).toInstant()
        assertThat(Duration.between(expected, opened.now).abs()).isLessThan(Duration.ofSeconds(1))
        assertThat(GroupDaySnapshot.parseFlat(DATE, opened.fields).version).isEqualTo(BoardFixtures.VERSION)
        assertThat(repoRead(LocalTime.of(6, 59, 59), BoardReadMode.BOARD)).isEqualTo(BoardRead.Rejected(ErrorCode.GAME_NOT_OPEN))
        assertThat(repoRead(LocalTime.of(8, 0, 1), BoardReadMode.BOARD)).isEqualTo(BoardRead.Rejected(ErrorCode.GAME_NOT_OPEN))
    }

    @Test
    fun `저장소 TICKET — 08시 00분 01초는 통과(fields 빔), 08시 00분 04초는 GAME_NOT_OPEN`() {
        fourMembers()
        val opened = repoRead(LocalTime.of(8, 0, 1), BoardReadMode.TICKET) as BoardRead.Opened
        assertThat(opened.fields).isEmpty()
        assertThat(repoRead(LocalTime.of(8, 0, 4), BoardReadMode.TICKET)).isEqualTo(BoardRead.Rejected(ErrorCode.GAME_NOT_OPEN))
    }

    @Test
    fun `저장소 STREAM — 창이 없다(08시 30분에도 Hash 를 읽는다)`() {
        fourMembers()
        assertThat(repoRead(LocalTime.of(8, 30), BoardReadMode.STREAM)).isInstanceOf(BoardRead.Opened::class.java)
    }

    @Test
    fun `저장소 — 거부 코드를 ErrorCode 로`() {
        fourMembers()
        assertThat(repoRead(LocalTime.of(7, 30), BoardReadMode.BOARD, uid = 999L))
            .isEqualTo(BoardRead.Rejected(ErrorCode.NOT_PARTICIPANT))
    }
}
