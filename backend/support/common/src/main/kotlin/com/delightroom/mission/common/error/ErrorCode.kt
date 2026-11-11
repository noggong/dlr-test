package com.delightroom.mission.common.error

/**
 * 클라이언트에 내려가는 오류 코드. HTTP 상태는 숫자로만 들고 Spring 에 의존하지 않는다.
 *
 * 코드 · HTTP 상태는 docs/api-guide.md §4 표 그대로다(openapi `ErrorCode` 와 같은 순서).
 * 코드별 발생 조건은 기능별 SPEC(docs/spec)이 정한다.
 */
enum class ErrorCode(val httpStatus: Int, val defaultMessage: String) {
    // 공통
    INVALID_REQUEST(400, "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."), // access token 없음 · 만료 · 위조

    // 가입 · 로그인 (auth-signup)
    INVALID_CREDENTIALS(401, "아이디 또는 비밀번호가 맞지 않습니다."), // AS10
    ALREADY_LOGGED_IN(409, "다른 기기에서 로그인 중입니다."), // AS9 · prd D16
    LOGIN_ID_TAKEN(409, "이미 사용 중인 아이디입니다."),
    GROUP_NAME_TAKEN(409, "이미 있는 그룹명입니다."), // AS5 · R-G2
    GROUP_NOT_FOUND(404, "그룹을 찾을 수 없습니다."), // R-G1
    GROUP_FULL(409, "그룹이 꽉 찼습니다."), // AS6 · R-G3

    // 게임 시간 · 참가 · 미션 (game-session · solve-signal · realtime-board)
    GAME_NOT_OPEN(409, "게임 시간이 아닙니다."), // GS2 · RB1 · prd D14
    NOT_PARTICIPANT(403, "오늘 게임의 그룹원이 아닙니다."), // GS3 · R-G4
    SOLO_GROUP(409, "그룹원이 2명 이상이어야 미션을 시작할 수 있습니다."), // GS4 · R-G6
    MISSION_NOT_STARTED(409, "미션을 시작하지 않았습니다."), // SS1 · prd D14
    MISSION_ALREADY_FINISHED(409, "이미 끝난 미션입니다."), // GS9 · SS1
    QUESTIONS_EXHAUSTED(409, "오늘은 문제가 준비되지 않았습니다."), // GS6

    // 신호 (game-session 진입 · solve-signal)
    QUESTION_OUT_OF_ORDER(409, "문제 순서가 맞지 않습니다."), // GS9 · SS5
    QUESTION_MISMATCH(409, "지금 문제와 다른 문제입니다."), // GS11 · SS5
    PASS_ALREADY_USED(409, "오늘 패스를 이미 썼습니다."), // SS9 · R-P5
    SUBMISSION_CLOSED(409, "제출이 마감되었습니다."), // SS3 · prd D1

    // 실시간 (SSE 티켓)
    REALTIME_TICKET_INVALID(401, "실시간 연결 티켓이 없거나 만료되었습니다."), // RB4

    // 결과 (deadline-result)
    NO_RESULT_YET(404, "아직 확정된 게임 결과가 없습니다."), // DR1

    // 처리하지 못한 예외 (platform — prd D21 · LLD platform §4.1). message 는 고정 문장이고 내부 사정을 싣지 않는다
    INTERNAL_ERROR(500, "서버에 문제가 생겼습니다."),
    UNAVAILABLE(503, "잠시 서비스를 이용할 수 없습니다. 잠시 뒤 다시 시도해 주세요."), // Redis · PostgreSQL · 그룹 채널 장애

    // 스켈레톤
    NOT_IMPLEMENTED(501, "아직 구현되지 않았습니다."),
}
