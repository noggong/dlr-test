package com.delightroom.mission.infra.redis.signal

import com.delightroom.mission.infra.redis.signal.GroupHashFixture.Member
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** ORACLE solve-signal O27 · O28 · I2 의 판정 부분 — `lib/decide.lua` 를 테스트 래퍼 `decide_probe.lua` 로 시각을 직접 넣어 부른다. */
class DecideScriptTest : SignalScriptSupport() {
    private val json = jacksonObjectMapper()

    @Test
    fun `O27 전원 SUCCEEDED 면 SUCCEEDED · decidedAt · 랭킹 · rankingFinal 1`() {
        val g = Group().write(
            Member(1, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)),
            Member(2, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
        )
        val t = hms(7, 40)

        assertThat(g.decide(t)).isEqualTo("SUCCEEDED")
        val h = g.hash()
        assertThat(h["result"]).isEqualTo("SUCCEEDED")
        assertThat(h["decidedAt"]).isEqualTo(t.toString())
        assertThat(h["rankingFinal"]).isEqualTo("1")
        val ranking: List<Map<String, Any?>> = json.readValue(h["ranking"]!!)
        assertThat(ranking.map { it["id"] to it["r"] }).containsExactly("2" to 1, "1" to 2)
        assertThat(h["version"]).isEqualTo("0") // version 은 부른 Lua 가 올린다
    }

    @Test
    fun `O27 아직 완료하지 않은 그룹원이 있으면 결과 없음`() {
        listOf("IN_PROGRESS", "NOT_STARTED", "FAILED").forEach { st ->
            val g = Group().write(
                Member(1, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)),
                Member(2, "bob", st = st, p = if (st == "NOT_STARTED") -1 else 0),
            )
            assertThat(g.decide(hms(7, 40))).isEmpty()
            assertThat(g.hash()).doesNotContainKeys("result", "decidedAt", "ranking", "rankingFinal")
        }
    }

    @Test
    fun `O28 부정 실패가 섞이면 FAILED_IMMEDIATE`() {
        val g = Group().write(
            Member(1, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)),
            Member(2, "bob", st = "CHEAT_FAILED", p = 0),
            Member(3, "cy", st = "CHEAT_FAILED", p = 2, ls = hms(7, 15)),
        )
        assertThat(g.decide(hms(7, 40))).isEqualTo("FAILED_IMMEDIATE")
        val ranking: List<Map<String, Any?>> = json.readValue(g.field("ranking")!!)
        assertThat(ranking.map { it["u"] }).containsExactly("amy", "bob", "cy")
        assertThat(ranking.map { it["s"] }).containsExactly("SUCCEEDED", "CHEAT_FAILED", "CHEAT_FAILED")
    }

    @Test
    fun `I2 결과가 이미 있으면 다시 쓰지 않는다 — HSETNX 방어선`() {
        val g = Group().write(
            Member(1, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)),
            Member(2, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
        )
        assertThat(g.decide(hms(7, 40))).isEqualTo("SUCCEEDED")
        val before = g.hash()

        assertThat(g.decide(hms(7, 50))).isEmpty()
        assertThat(g.hash()).isEqualTo(before)
    }

    @Test
    fun `O29 08_00 이후 결과면 rankingFinal 0 · pending 지움`() {
        val g = Group().write(
            Member(1, "amy", st = "SUCCEEDED", p = 3, ls = hms(8, 0, 1)),
            Member(2, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
            group = mapOf("pending" to "1"),
        )
        assertThat(g.decide(hms(8, 0, 1))).isEqualTo("SUCCEEDED")
        assertThat(g.field("rankingFinal")).isEqualTo("0")
        assertThat(g.hash()).doesNotContainKey("pending")
    }

    @Test
    fun `O27 명단의 # 칸은 그룹원이 아니다`() {
        val g = Group().write(
            Member(1, "amy", st = "SUCCEEDED", p = 3, ls = hms(7, 30)),
            Member(2, "bob", st = "SUCCEEDED", p = 3, ls = hms(7, 20)),
        )
        assertThat(g.decide(hms(7, 40))).isEqualTo("SUCCEEDED")
        val ranking: List<Map<String, Any?>> = json.readValue(g.field("ranking")!!)
        assertThat(ranking).hasSize(2)
    }
}
