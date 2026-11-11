package com.delightroom.mission.core.domain.ranking

import com.delightroom.mission.core.domain.user.LoginId

/**
 * 랭킹 입력 한 줄(LLD deadline-result §4.2 · SPEC DR6). Lua `lib/ranking.lua` 의 `m` 과 같은 키다.
 * `FAILED` 는 키가 아니다 — 진행도를 그대로 비교한다.
 */
data class RankingKey(
    val loginId: LoginId,
    /** 상태가 `CHEAT_FAILED` 인가 — 0순위. */
    val cheatFailed: Boolean,
    /** 진행도 -1(미접속) ~ 3. */
    val progress: Int,
    /** 마지막 풀이 도착 시각(에포크 마이크로초). 진행도 1~3 에서만 본다. */
    val lastSolvedAtUs: Long?,
    /** 패스를 쓴 문제 번호. 안 썼으면 null. */
    val passNo: Int?,
    /** 지각(첫 문제은행 도착 > 07:00:03 — D8). 시작 전이면 false. */
    val late: Boolean,
) {
    init {
        require(progress in -1..3) { "progress 는 -1~3: $progress" }
        require(progress < 1 || lastSolvedAtUs != null) { "진행도 1~3 은 lastSolvedAtUs 가 있어야 한다: ${loginId.value}" }
    }
}

/** 순위가 붙은 한 줄. */
data class RankedKey(
    val key: RankingKey,
    val rank: Int,
)
