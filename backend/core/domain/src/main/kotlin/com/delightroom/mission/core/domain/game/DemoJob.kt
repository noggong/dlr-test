package com.delightroom.mission.core.domain.game

/**
 * 데모 시각 이동이 기다리는 경계의 일(prd D26 · LLD platform §4.4-3 · 5). [key] 는 Redis Hash `demo:jobs` 필드 이름의 앞부분이다.
 * 마감은 인스턴스가 여럿일 수 있어 필드가 `<key>:<index>/<count>` 다 — 모든 인스턴스가 끝내야 그 경계가 끝난 것이다.
 */
enum class DemoJob(val key: String) {
    /** 07:00 — `worker: member` 그날 명단 확정. */
    MEMBER("member"),

    /** 08:00 — `worker: deadline` 1차. */
    DEADLINE_FIRST("deadline-first"),

    /** 08:00:03 — `worker: deadline` 2차(랭킹 확정). */
    DEADLINE_SECOND("deadline-second"),
    ;

    /** `demo:jobs` 필드 — 명단은 하나, 마감은 인스턴스마다. */
    fun field(instanceIndex: Int = 0, instanceCount: Int = 1): String =
        if (this == MEMBER) key else "$key:$instanceIndex/$instanceCount"
}
