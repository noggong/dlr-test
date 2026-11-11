package com.delightroom.mission.core.domain.ranking

/**
 * 랭킹 규칙(prd §6 · HLD deadline-result §4 · SPEC DR6 · DR7). Lua `lib/ranking.lua` 와 같은 구조다(LLD §4.2) —
 * 한쪽을 고치면 다른 쪽도 고치고 ORACLE O1 ~ O3 을 다시 돌린다.
 */
object Ranking {
    /** 음수: a 가 위 · 양수: b 가 위 · 0: 동률. 앞 키가 같을 때만 다음 키를 본다. */
    fun compare(a: RankingKey, b: RankingKey): Int {
        if (a.cheatFailed != b.cheatFailed) return if (a.cheatFailed) 1 else -1 // 0순위
        if (a.cheatFailed) return 0 // 부정 실패끼리 동률(끝)
        if (a.progress != b.progress) return if (a.progress > b.progress) -1 else 1 // 1순위 진행도
        if (a.progress >= 1) {
            val la = a.lastSolvedAtUs!!
            val lb = b.lastSolvedAtUs!!
            if (la != lb) return if (la < lb) -1 else 1 // 2순위 도착 시각(µs)
            return comparePass(a.passNo, b.passNo, bothUsedByNumber = true) // 3순위
        }
        if (a.progress == 0) {
            if (a.late != b.late) return if (a.late) 1 else -1 // 2′ 지각 아님
            return comparePass(a.passNo, b.passNo, bothUsedByNumber = false) // 3′
        }
        return 0 // 진행도 -1 동률
    }

    /** 패스 안 쓴 쪽이 위. 둘 다 썼으면 진행도 1~3 은 큰 번호가 위, 진행도 0 은 동률. */
    private fun comparePass(a: Int?, b: Int?, bothUsedByNumber: Boolean): Int {
        if ((a == null) != (b == null)) return if (a == null) -1 else 1
        if (a == null || b == null || !bothUsedByNumber || a == b) return 0
        return if (a > b) -1 else 1
    }

    /**
     * 경쟁 순위(1, 1, 3)를 붙여 표시 순서로 돌려준다. 같은 순위 안은 로그인 아이디 순 —
     * 아이디가 ASCII 라 `String.compareTo` 가 Lua 의 바이트 순과 같다.
     */
    fun rank(keys: Collection<RankingKey>): List<RankedKey> {
        val sorted = keys.sortedWith { a, b ->
            compare(a, b).takeIf { it != 0 } ?: a.loginId.value.compareTo(b.loginId.value)
        }
        val ranked = ArrayList<RankedKey>(sorted.size)
        sorted.forEachIndexed { i, key ->
            val rank = if (i > 0 && compare(sorted[i - 1], key) == 0) ranked[i - 1].rank else i + 1
            ranked += RankedKey(key, rank)
        }
        return ranked
    }
}
