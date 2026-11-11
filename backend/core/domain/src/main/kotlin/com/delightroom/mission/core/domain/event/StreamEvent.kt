package com.delightroom.mission.core.domain.event

/**
 * 이벤트 Stream `ev:{<b>}` 의 메시지 한 건(HLD platform §4.2) — `worker: persister` 가 읽는다.
 *
 * [fields] 는 Stream 칸 그대로(값은 모두 문자열)다. 트림으로 지워졌는데 PEL 에만 남은 id 는 [fields] 가 비어 있다.
 */
data class StreamEvent(
    /** Stream id(`<ms>-<seq>`). */
    val id: String,
    val fields: Map<String, String>,
) {
    /** 공통 칸 `type`(예: `solve.applied`). 없으면 null. */
    val type: String? get() = fields["type"]

    /** 지워진 항목 — 처리할 칸이 없다. */
    val deleted: Boolean get() = fields.isEmpty()
}

/** `XAUTOCLAIM` 한 번의 결과 — [cursor] 가 `0-0` 이면 끝까지 훑었다. */
data class ClaimedEvents(
    val cursor: String,
    val events: List<StreamEvent>,
) {
    val done: Boolean get() = cursor == "0-0"
}
