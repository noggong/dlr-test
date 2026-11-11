package com.delightroom.mission.app.api.dto

import com.delightroom.mission.core.service.GameInfo
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.service.MissionStart
import com.delightroom.mission.core.service.ShownQuestion
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class GroupMemberResponse(
    /** 로그인 아이디(prd D5). */
    val userId: String,
)

data class GameGroupResponse(
    /** 그룹명(가입 때 입력 표기). */
    val name: String,
    /** 그날 그룹원 0~10명, userId 오름차순(GS3). 내가 그날 06:50 부터 가입했으면 나는 없다(prd D24). */
    val members: List<GroupMemberResponse>,
    /** 늦은 그룹원 0~10명, userId 오름차순 — 그날 명단 밖(06:50 부터 가입). 표기 전용(GS15). */
    val laterMembers: List<GroupMemberResponse>,
)

/** `GET /api/v1/games/today` — 게임 정보(game-session SPEC §2.1). */
data class GameInfoResponse(
    /** 게임 날짜 — 08:00 이후면 다음 날(GS1). */
    val date: LocalDate,
    val startAt: OffsetDateTime,
    val deadlineAt: OffsetDateTime,
    /** 클라이언트 카운트다운의 기준(밀리초까지). */
    val serverTime: OffsetDateTime,
    val group: GameGroupResponse,
) {
    companion object {
        fun of(info: GameInfo, zone: ZoneId): GameInfoResponse =
            GameInfoResponse(
                date = info.date,
                startAt = info.startAt.atZone(zone).toOffsetDateTime(),
                deadlineAt = info.deadlineAt.atZone(zone).toOffsetDateTime(),
                serverTime = info.serverTime.truncatedTo(ChronoUnit.MILLIS).atZone(zone).toOffsetDateTime(),
                group = GameGroupResponse(
                    name = info.groupName.value,
                    members = info.members.map { GroupMemberResponse(it.value) },
                    laterMembers = info.laterMembers.map { GroupMemberResponse(it.value) },
                ),
            )
    }
}

data class QuestionResponse(
    /** 문제 PK(hash) — 소문자 hex 64자. */
    val questionId: String,
    /** 1 · 2 · 3 = 그 문제를 낼 번호(R-B5). */
    val difficulty: Int,
    /** 문제 본문(주관식). */
    val body: String,
    /** CURRENT · POLITICS · SOCIETY · GENERAL. */
    val category: String,
    /** SHA-256(UTF-8("<questionId>:<정규화한 답>")) 소문자 hex(prd D7). 정답은 내려주지 않는다. */
    val answerHash: String,
)

/** `POST /api/v1/games/today/question-bank` — 문제은행(= 미션 시작)(game-session SPEC §2.2). */
data class QuestionBankResponse(
    /** 그날 처음 받은 순간의 서버 시각(마이크로초). 다시 받아도 같은 값(GS5 · GS7). */
    val missionStartedAt: OffsetDateTime,
    /** 난이도 1 → 2 → 3 순서, 난이도마다 1~20개(GS6). */
    val questions: List<QuestionResponse>,
) {
    companion object {
        fun of(start: MissionStart, zone: ZoneId): QuestionBankResponse =
            QuestionBankResponse(
                // 시작 시각은 Redis µs 그대로(SPEC §2.2 — 마이크로초까지)
                missionStartedAt = start.startedAt.truncatedTo(ChronoUnit.MICROS).atZone(zone).toOffsetDateTime(),
                questions = start.questions.map {
                    QuestionResponse(it.id.value, it.difficulty.level, it.body, it.category.name, it.answerHash)
                },
            )
    }
}

/** `POST /api/v1/games/today/shown` — 진입 신호(game-session SPEC §2.3). 타입을 직접 보려고 `Any?` 로 받는다(SolveRequest 와 같다). */
data class ShownRequest(
    /** JSON 정수 1 · 2 · 3 — 진행도 + 1 이어야 한다(GS9). `"1"` · `1.5` 는 400. */
    val questionNo: Any?,
    /** JSON 문자열 `^[0-9a-f]{64}$`. */
    val questionId: Any?,
) {
    /** 본문 규칙(SPEC §2.3) — 어기면 `400 INVALID_REQUEST`. 서비스 · Redis 를 부르기 전에 검사한다(ORACLE O19). */
    fun validated(): Pair<Int, QuestionId> = questionNo(questionNo) to questionId(questionId)
}

data class ShownResponse(
    val questionNo: Int,
    val questionId: String,
    /** 0~2 = questionNo − 1(GS13). */
    val progress: Int,
    /** MemberStatus — 200 이면 언제나 IN_PROGRESS. */
    val missionStatus: String,
) {
    companion object {
        fun of(shown: ShownQuestion) =
            ShownResponse(shown.questionNo, shown.questionId.value, shown.progress.coerceAtLeast(0), shown.missionStatus.name)
    }
}
