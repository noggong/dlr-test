package com.delightroom.mission.common.error

/**
 * 클라이언트에 내려가는 오류 코드. HTTP 상태는 숫자로만 들고 Spring 에 의존하지 않는다.
 *
 * 초안이다 — 코드별 발생 조건 · 클라이언트 대응은 기능별 SPEC 문서가 확정한다.
 * 근거가 되는 PRD 규칙을 주석에 적었다.
 */
enum class ErrorCode(val httpStatus: Int, val defaultMessage: String) {
    // 공통
    INVALID_REQUEST(400, "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."), // FR-8
    NOT_IMPLEMENTED(501, "아직 구현되지 않았습니다."), // 스켈레톤

    // 가입 · 그룹 (§5.1)
    LOGIN_ID_TAKEN(409, "이미 사용 중인 ID 입니다."),
    GROUP_NAME_TAKEN(409, "이미 있는 그룹명입니다."), // R-G2
    GROUP_NOT_FOUND(404, "그룹을 찾을 수 없습니다."), // R-G1
    GROUP_FULL(409, "그룹이 꽉 찼습니다."), // R-G3
    INVALID_CREDENTIALS(401, "ID 또는 비밀번호가 올바르지 않습니다."), // FR-8

    // 게임 시간 · 미션 (§3 · §5.2)
    GAME_NOT_OPEN(409, "게임 시간이 아닙니다."), // §3
    SOLO_GROUP(409, "그룹원이 2명 이상이어야 미션을 시작할 수 있습니다."), // R-G6
    MISSION_NOT_STARTED(409, "미션을 시작하지 않았습니다."), // R-F2 · prd D14
    MISSION_ALREADY_FINISHED(409, "이미 끝난 미션입니다."), // §4.1
    NO_RESULT_YET(404, "아직 확정된 게임 결과가 없습니다."), // R-V2 · openapi getLatestResult

    // 신호 (§5.4 ~ §5.6)
    QUESTION_OUT_OF_ORDER(409, "문제 순서가 맞지 않습니다."), // R-S4
    PASS_ALREADY_USED(409, "오늘 패스를 이미 썼습니다."), // R-P5
    SUBMISSION_CLOSED(409, "제출이 마감되었습니다."), // R-D2 · NFR-1

    // 실시간 (SSE 티켓)
    REALTIME_TICKET_INVALID(401, "실시간 연결 티켓이 없거나 만료되었습니다."),
}
