package com.delightroom.mission.core.testfixture

import com.delightroom.mission.core.domain.ranking.RankingKey
import com.delightroom.mission.core.domain.user.LoginId
import kotlin.random.Random

/**
 * 랭킹 무작위 그룹(ORACLE deadline-result O3 · I6 · I7). Kotlin `RankingTest` 와 Lua `LuaRankingTest` 가
 * 같은 시드로 같은 그룹을 받는다 — 두 쪽 입력이 다르면 일치 판정이 뜻이 없다.
 *
 * 그룹 인원 2~10, 상태 5종 · 진행도 -1~3 · 패스 · 지각을 섞고, 도착 시각은 그룹마다 µs 단위로 몇 개 값만
 * 쓰게 해 동시각 · 1µs 차이가 자주 나오게 한다. 키는 [RankingKey] 불변식(진행도 1~3 이면 시각 있음)을 지킨다.
 */
object RankingRandomGroups {
    const val SEED = 20261111L
    const val COUNT = 10_000

    /** 그룹 하나 — 상태는 랭킹 키(`cheatFailed`)를 만든 원래 값이라 함께 둔다. */
    data class Group(
        val index: Int,
        val members: List<Member>,
    ) {
        val keys: List<RankingKey> get() = members.map { it.key }
    }

    data class Member(
        val status: String,
        val key: RankingKey,
    )

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789_"
    /** 2026-11-11 07:20:00 KST(µs) 언저리. */
    private const val BASE_US = 1_794_349_200_000_000L

    fun generate(seed: Long = SEED, count: Int = COUNT): List<Group> {
        val random = Random(seed)
        return List(count) { group(it, random) }
    }

    private fun group(index: Int, random: Random): Group {
        val size = random.nextInt(2, 11)
        // 시각 후보 몇 개(1µs 이웃 포함) — 동시각 · 근소 차이를 자주 만든다
        val base = BASE_US + random.nextLong(0, 3_600_000_000L)
        val times = List(random.nextInt(1, 4)) { base + random.nextLong(0, 3) }
        val logins = LinkedHashSet<String>()
        while (logins.size < size) logins += loginId(random)
        return Group(index, logins.map { member(it, random, times) })
    }

    private fun member(login: String, random: Random, times: List<Long>): Member {
        val progress = random.nextInt(-1, 4)
        val status = when {
            progress == -1 -> listOf("NOT_STARTED", "FAILED").random(random)
            progress == 3 -> listOf("SUCCEEDED", "SUCCEEDED", "IN_PROGRESS", "CHEAT_FAILED").random(random)
            else -> listOf("IN_PROGRESS", "FAILED", "CHEAT_FAILED").random(random)
        }
        val started = progress >= 0
        val key = RankingKey(
            loginId = LoginId(login),
            cheatFailed = status == "CHEAT_FAILED",
            progress = progress,
            lastSolvedAtUs = if (progress >= 1) times.random(random) else null,
            passNo = if (started && random.nextInt(3) == 0) random.nextInt(1, 4) else null,
            late = started && random.nextInt(4) == 0,
        )
        return Member(status, key)
    }

    /** 4~6자 — 밑줄 · 숫자가 섞여 바이트 순과 사전 순이 갈리는 경우가 나온다. 짧은 알파벳으로 접두가 자주 겹친다. */
    private fun loginId(random: Random): String {
        val length = random.nextInt(4, 7)
        val alphabet = if (random.nextBoolean()) ID_CHARS else "ab1_"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
