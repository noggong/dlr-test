package com.delightroom.mission.core.domain.question

import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** 은행 뽑기 결과(LLD game-session §4.4). */
sealed interface BankDraw {
    data class Built(val bank: QuestionBank) : BankDraw

    /** [difficulty] 에 이 유저의 미출제 문제가 0개 — 은행을 만들지 않는다(SPEC GS6 · `QUESTIONS_EXHAUSTED`). */
    data class Exhausted(val difficulty: Difficulty) : BankDraw
}

/**
 * 은행 뽑기(SPEC GS6 · prd D10 · LLD §4.4) — 순수 함수. `api` 대체 경로와 `worker: prepare-questions` 가 같이 쓴다.
 * 난이도마다 후보 = 문제 캐시 − 출제 이력, 후보가 [size] 개 이하면 전부(섞어서), 넘으면 부분 Fisher–Yates 로 [size] 개.
 */
object QuestionBankBuilder {
    fun build(
        idsByDifficulty: Map<Difficulty, LongArray>,
        served: Set<Long>,
        size: Int,
        random: Random = ThreadLocalRandom.current().asKotlinRandom(),
    ): BankDraw {
        require(size > 0) { "size 는 1 이상이다: $size" }
        val bank = LinkedHashMap<Difficulty, List<Long>>()
        for (d in Difficulty.entries) {
            val candidates = idsByDifficulty[d]?.filterNotTo(ArrayList()) { it in served } ?: arrayListOf()
            if (candidates.isEmpty()) return BankDraw.Exhausted(d)
            bank[d] = pick(candidates, size, random)
        }
        return BankDraw.Built(QuestionBank(bank))
    }

    /** 부분 Fisher–Yates — 앞 `min(size, n)` 칸만 섞어 그만큼 자른다. [candidates] 를 바꾼다. */
    private fun pick(candidates: ArrayList<Long>, size: Int, random: Random): List<Long> {
        val n = minOf(size, candidates.size)
        for (i in 0 until n) {
            val j = random.nextInt(i, candidates.size)
            candidates[i] = candidates[j].also { candidates[j] = candidates[i] }
        }
        return candidates.subList(0, n).toList()
    }
}
