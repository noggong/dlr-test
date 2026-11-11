# SPEC — game-session

> 판 v2.2 · 2026-11-11 (v2.2: 게임 정보에 `group.laterMembers` — 그날 명단에 들지 않은 그룹원(06:50 부터 가입)도 그룹원 표기에 보이게(GS3 · GS15). 진행 · 결과 · 랭킹은 그대로 그날 명단만 · v2.1: 그날 그룹원 기준을 `joined_at < D 06:50`(GS3 · §2.1 · 오류표 · GS6) — prd D24 · v2: T-GS-4 리뷰 — GS11 패스로 넘긴 문제는 그 번호에 다시 고정하지 않는다(패스 앞 진입 신호의 늦은 재시도) · `MISSION_ALREADY_FINISHED` 조건에 `FAILED` 주석) · 짝 문서: [HLD](../hld/game-session.md) · [LLD](../lld/game-session.md) · [SPEC](../spec/game-session.md) · [ORACLE](../oracle/game-session.md) · 근거: [PRD](../prd.md) v10 FR-1 · FR-3 · §5.2 · D6 · D8 · D10 · D14 · D15 · D18 · 공통 규약: [API 가이드](../api-guide.md)

**세 줄 요약**
- 외부 계약은 `api` 엔드포인트 3개 — 게임 정보 `GET /api/v1/games/today` · 문제은행(= 미션 시작) `POST /api/v1/games/today/question-bank` · 진입 신호 `POST /api/v1/games/today/shown`. 셋 다 쿠키 `access` 필수.
- 입력 검증은 진입 신호 본문뿐이다(`questionNo` 1 · 2 · 3, `questionId` 64자 소문자 hex). 시간 창은 07:00:00 ≤ 서버 시각 < 08:00:00(Redis `TIME`), 유예 없음.
- 오류 코드: `INVALID_REQUEST` · `UNAUTHORIZED` · `GAME_NOT_OPEN` · `SUBMISSION_CLOSED` · `NOT_PARTICIPANT` · `SOLO_GROUP` · `QUESTIONS_EXHAUSTED` · `MISSION_NOT_STARTED` · `MISSION_ALREADY_FINISHED` · `QUESTION_MISMATCH` · `QUESTION_OUT_OF_ORDER`(뜻은 api-guide §4). 규칙은 §3 의 GS1 ~ GS15.

## 1. 엔드포인트
| 메서드 | 경로 | 인증 | 설명 | 규칙 |
|---|---|---|---|---|
| GET | `/api/v1/games/today` | 쿠키 `access` | 게임 날짜 · 시작 · 마감 · 서버 시각 · 그룹명 · 그날 그룹원 · 늦은 그룹원 | GS1 · GS3 · GS15 |
| POST | `/api/v1/games/today/question-bank` | 쿠키 `access` | 문제은행 받기. 그날 처음 받은 순간이 미션 시작 | GS2 ~ GS8 · GS14 |
| POST | `/api/v1/games/today/shown` | 쿠키 `access` | 진입 신호 — 이 번호의 지금 문제를 고정 | GS2 · GS3 · GS4 · GS9 ~ GS13 |

공통: 본문은 `application/json; charset=utf-8`, 시각은 ISO-8601 `+09:00`, 날짜는 `YYYY-MM-DD`(KST 게임 날짜). 오류 본문은 언제나 `{ "code": string(ErrorCode), "message": string }` 이고 클라이언트는 `code` 로만 분기한다. 쿠키 `access` 가 없거나 만료 · 위조면 모든 엔드포인트가 `401 UNAUTHORIZED` 다(가장 먼저 본다).

## 2. 요청 · 응답

### 2.1 `GET /api/v1/games/today` — 게임 정보
앱을 열 때 · 대기 화면 카운트다운에 쓴다. 상태를 바꾸지 않는다. 07:00 전에는 「지금까지 그 날짜 06:50 전에 가입한 사람」(06:50 부터는 늘지 않는다), 07:00 이후에는 07:00 에 확정된 그날 그룹원이다(GS3). 08:00 이후에는 다음 날 게임을 준다(GS1).

**요청**
| 위치 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 헤더 | `Cookie: access=<JWT>` | string | 예 | 유효한 access token | 로그인 쿠키 |
| 쿼리 · 본문 | — | — | — | — | 없다 |

**응답 `200`**
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `date` | string(date) | 예 | `YYYY-MM-DD` | 게임 날짜(GS1) |
| `startAt` | string(date-time) | 예 | `date` 의 07:00:00+09:00 | 미션 시작 시각 |
| `deadlineAt` | string(date-time) | 예 | `date` 의 08:00:00+09:00 | 마감 시각(제출 유예는 solve-signal 의 D1) |
| `serverTime` | string(date-time) | 예 | 밀리초까지 | 응답을 만든 서버 시각 — 클라이언트 카운트다운의 기준(기기 시계를 쓰지 않는다) |
| `group` | object | 예 | | 내 그룹 |
| `group.name` | string | 예 | 2~20자 | 그룹명(가입 때 입력 표기 — prd D11) |
| `group.members` | array | 예 | 0~10개, `userId` 오름차순 | 그날 그룹원(GS3). 내가 그날 06:50 부터 가입했으면 나는 없다(그날 참가자 아님 — prd D24). 1개면 1명 그룹(GS4) |
| `group.members[].userId` | string | 예 | `^[a-z0-9_]{4,20}$` | 로그인 아이디(prd D5) |
| `group.laterMembers` | array | 예 | 0~10개, `userId` 오름차순 | 그 게임 날짜의 그날 그룹원이 아닌 그룹원 = 그 날짜 06:50 부터 가입한 사람(GS15). 화면의 그룹원 표기에 「내일부터」로만 쓴다 — 미션 · 진행 · 결과 · 랭킹에는 들지 않는다. `members` 와 겹치지 않는다 |
| `group.laterMembers[].userId` | string | 예 | `^[a-z0-9_]{4,20}$` | 로그인 아이디(prd D5) |

**오류**
| code | HTTP | 조건 |
|---|---|---|
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 |

**요청 예시**
```bash
curl -s -b 'access=<JWT>' http://localhost:8080/api/v1/games/today
```

**응답 예시** `200` (06:55 — 07:00 전)
```json
{
  "date": "2026-11-12",
  "startAt": "2026-11-12T07:00:00+09:00",
  "deadlineAt": "2026-11-12T08:00:00+09:00",
  "serverTime": "2026-11-12T06:55:12.345+09:00",
  "group": {
    "name": "아침수영",
    "members": [{ "userId": "dohyun" }, { "userId": "jiwoo_k" }, { "userId": "minseo01" }],
    "laterMembers": [{ "userId": "seoyun" }]
  }
}
```

### 2.2 `POST /api/v1/games/today/question-bank` — 문제은행(= 미션 시작)
그날 처음 성공한 호출이 미션 시작이다(진행도 -1 → 0, 상태 `IN_PROGRESS`, 시작 시각 · 지각 고정 — GS5). 같은 날 다시 부르면(재접속 · 재시도) 아무것도 바꾸지 않고 같은 시작 시각 · 같은 은행을 준다(GS7). 상태를 바꾸므로 POST 다.

**요청**
| 위치 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 헤더 | `Cookie: access=<JWT>` | string | 예 | 유효한 access token | 로그인 쿠키 |
| 본문 | — | — | — | 비워 보낸다(보내도 무시) | 없다 |

**응답 `200`**
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `missionStartedAt` | string(date-time) | 예 | 그날 07:00:00 ≤ 값 < 08:00:00, 마이크로초까지 | 그날 미션 시작 시각 — 처음 받은 순간의 서버 시각(Redis `TIME`). 다시 받아도 같은 값 |
| `questions` | array | 예 | 3~60개. 난이도 1 → 2 → 3 순서, 난이도마다 1~20개 | 문제은행(GS6) |
| `questions[].questionId` | string | 예 | `^[0-9a-f]{64}$` | 문제 PK(hash) — 진입 · 풀이 · 패스 신호에 그대로 보낸다 |
| `questions[].difficulty` | integer | 예 | `1` · `2` · `3` | 난이도 = 그 문제를 낼 번호(R-B5) |
| `questions[].body` | string | 예 | 비어 있지 않음 | 문제 본문(주관식) |
| `questions[].category` | string(enum) | 예 | `CURRENT` · `POLITICS` · `SOCIETY` · `GENERAL` | 분야 — 시사 · 정치 · 사회 · 상식 |
| `questions[].answerHash` | string | 예 | `^[0-9a-f]{64}$` | 정답 해시 = `SHA-256(UTF-8("<questionId>:<정규화한 답>"))` 소문자 hex(prd D7). 실제 정답은 내려주지 않는다 |

**오류** (판정 순서는 GS14)
| code | HTTP | 조건 |
|---|---|---|
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 |
| `GAME_NOT_OPEN` | 409 | 서버 시각 < 그날 07:00:00 (D14) |
| `SUBMISSION_CLOSED` | 409 | 서버 시각 ≥ 그날 08:00:00 |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님 — 그날 06:50 부터 가입(GS3 · prd D24) |
| `SOLO_GROUP` | 409 | 그날 그룹원이 1명(GS4) |
| `QUESTIONS_EXHAUSTED` | 409 | 은행을 새로 만들어야 하는데 어떤 난이도의 미출제 문제가 0개(GS6). 미션 시작은 이미 반영돼 있다(GS5) |

**요청 예시**
```bash
curl -s -X POST -b 'access=<JWT>' http://localhost:8080/api/v1/games/today/question-bank
```

**응답 예시** `200` (난이도마다 1개로 줄여 보인다 — 실제는 난이도마다 최대 20개)
```json
{
  "missionStartedAt": "2026-11-12T07:00:01.204518+09:00",
  "questions": [
    {
      "questionId": "032a4ed4d015d003a07a04124ce2b3513e71b986c3cf279104111ced399b366f",
      "difficulty": 1,
      "body": "대한민국 국회의원의 임기는 몇 년인가?",
      "category": "POLITICS",
      "answerHash": "a014f744dd124590cc33087fbd6de7dd64e3f07bc0894793a2465ff661aa328f"
    },
    {
      "questionId": "1d417a61538723a2022b4aac6fa7a835f90fa2dbc77cea68fbd2993e90fdc228",
      "difficulty": 2,
      "body": "기준금리를 결정하는 한국은행의 의결 기구는?",
      "category": "CURRENT",
      "answerHash": "3670753d1e03a0e876ac7b2f46669ec17a8363f3665da6b087b48e87dac7af83"
    },
    {
      "questionId": "c60159cef1ecfe0a89b6a09756cb873dbf2b8e6dc8c1e43496b998ed0eaa89e9",
      "difficulty": 3,
      "body": "합계출산율의 정의에서 기준이 되는 여성의 나이 범위는?",
      "category": "SOCIETY",
      "answerHash": "e5104f46c583f3adf57f21dc474a5639219782ef00ae04f8a71bd63007cffbc8"
    }
  ]
}
```

**오류 예시** `409`
```json
{ "code": "SOLO_GROUP", "message": "그룹원이 2명 이상이어야 미션을 시작할 수 있습니다." }
```

### 2.3 `POST /api/v1/games/today/shown` — 진입 신호
문제 화면에 들어갈 때마다(패스로 바꾼 문제 포함 · 재접속 포함) 보낸다(D6). 그 번호에 지금 문제가 없으면 이 문제로 고정하고, 같은 문제면 멱등으로 같은 응답을 준다(GS10). 풀이 · 패스 신호는 여기서 고정된 문제에만 받는다(solve-signal).

**요청**
| 위치 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 헤더 | `Cookie: access=<JWT>` | string | 예 | 유효한 access token | 로그인 쿠키 |
| 헤더 | `Content-Type` | string | 예 | `application/json` | |
| 본문 | `questionNo` | integer | 예 | `1` · `2` · `3` | 문제 번호 = 난이도(R-B5). 진행도 + 1 이어야 한다(GS9) |
| 본문 | `questionId` | string | 예 | `^[0-9a-f]{64}$` | 화면에 낸 문제 PK — 문제은행에서 그 번호 난이도의 문제 중 고른 것 |

**응답 `200`**
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `questionNo` | integer | 예 | `1` · `2` · `3` | 요청 값 그대로 |
| `questionId` | string | 예 | `^[0-9a-f]{64}$` | 이 번호의 지금 문제(= 요청 값) |
| `progress` | integer | 예 | 0~2 (= `questionNo − 1`) | 지금 진행도(GS13) |
| `missionStatus` | string(enum) | 예 | `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 내 미션 상태(MemberStatus). `200` 이면 언제나 `IN_PROGRESS` — 다른 값은 오류로 끝난다(GS9) |

**오류** (판정 순서는 GS9)
| code | HTTP | 조건 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 본문이 JSON 이 아님 · 필드 없음 · `questionNo` ∉ {1,2,3} · `questionId` 가 64자 소문자 hex 가 아님 |
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 |
| `GAME_NOT_OPEN` | 409 | 서버 시각 < 그날 07:00:00 |
| `SUBMISSION_CLOSED` | 409 | 서버 시각 ≥ 그날 08:00:00 |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님 |
| `SOLO_GROUP` | 409 | 그날 그룹원이 1명 |
| `MISSION_NOT_STARTED` | 409 | 그날 문제은행을 받기 전(진행도 -1) |
| `MISSION_ALREADY_FINISHED` | 409 | 내 상태가 `SUCCEEDED` · `CHEAT_FAILED`(`FAILED` 는 08:00 이후에만 생겨 시간 창에서 먼저 걸린다) |
| `QUESTION_MISMATCH` | 409 | 없는 문제 PK · 그 문제의 난이도 ≠ `questionNo` · 그 번호에 이미 다른 지금 문제가 고정됨 · 그 번호에서 패스로 넘긴 문제(GS11) |
| `QUESTION_OUT_OF_ORDER` | 409 | `questionNo` ≠ 진행도 + 1 (이미 푼 번호의 늦은 재시도 포함) |

**요청 예시**
```bash
curl -s -X POST -b 'access=<JWT>' -H 'Content-Type: application/json' \
  -d '{"questionNo":1,"questionId":"032a4ed4d015d003a07a04124ce2b3513e71b986c3cf279104111ced399b366f"}' \
  http://localhost:8080/api/v1/games/today/shown
```
```json
{ "questionNo": 1, "questionId": "032a4ed4d015d003a07a04124ce2b3513e71b986c3cf279104111ced399b366f" }
```

**응답 예시** `200`
```json
{
  "questionNo": 1,
  "questionId": "032a4ed4d015d003a07a04124ce2b3513e71b986c3cf279104111ced399b366f",
  "progress": 0,
  "missionStatus": "IN_PROGRESS"
}
```

**오류 예시** `409` (1번에 이미 다른 문제가 고정됨)
```json
{ "code": "QUESTION_MISMATCH", "message": "지금 문제와 다른 문제입니다." }
```

## 3. 규칙
「서버 시각」은 모두 Redis `TIME`(마이크로초) 이다 — 앱 서버 · 클라이언트 시계로 판정하지 않는다. 경계 시각은 KST.

| id | 규칙 | 엔드포인트 |
|---|---|---|
| GS1 | **게임 날짜**: 게임 정보의 `date` 는 서버 시각이 KST 로 그 날짜 08:00:00 전이면 그 날짜, 08:00:00 이후면 다음 날이다(대기 화면이 다음 게임까지 센다). 문제은행 · 진입 신호의 날짜는 서버 시각의 KST 날짜다 | 게임 정보 · 전체 |
| GS2 | **시간 창**: 문제은행 · 진입 신호는 `07:00:00.000000 ≤ 서버 시각 < 08:00:00.000000` 만 받는다. 전이면 `GAME_NOT_OPEN`(D14), 후면 `SUBMISSION_CLOSED`. 08:00 제출 유예(D1)는 풀이 신호에만 있다 | 문제은행 · 진입 |
| GS3 | **그날 그룹원** = 소속 그룹에서 `joined_at < 그 날짜 06:50:00.000000 KST` 인 유저(R-G4 · R-G5 · prd D24 — 기준 시각은 설정 `game.join-cutoff`). 06:49:59.999999 가입은 그날 그룹원, 06:50:00.000000 가입은 아니다. 07:00 에 명단으로 확정되어 그날 안에는 바뀌지 않는다. 06:50 부터 가입한 유저는 그날 `NOT_PARTICIPANT`, 다음 날부터 참가(가입 응답 `firstGameDate` — auth-signup SPEC §2.1) | 전체 |
| GS4 | **1명 그룹**: 그날 그룹원이 1명이면 문제은행 · 진입 신호를 `SOLO_GROUP` 으로 거부한다(R-G6). 게임 정보는 `200` 이고 `members` 가 1개다 | 문제은행 · 진입 |
| GS5 | **미션 시작 1회**: 그날 처음 `200` 을 받은 문제은행 요청의 서버 시각이 `missionStartedAt` 이고 그 뒤로 바뀌지 않는다(동시 요청 · 다른 기기 포함). **지각** = `missionStartedAt > 07:00:03.000000`(D8 — 정확히 07:00:03 은 지각 아님). 지각은 응답에 없고 랭킹에만 쓴다. `QUESTIONS_EXHAUSTED` 로 끝나도 시작은 반영된다 | 문제은행 |
| GS6 | **은행 구성**: 난이도 1 · 2 · 3 마다, 은행을 만드는 시점에 이 유저의 출제 이력(진입 신호로 화면에 나간 문제 전부 — D6)에 없는 문제 중 20개를 무작위로(모자라면 있는 만큼) 준다(D10). 한 난이도라도 0개면 `QUESTIONS_EXHAUSTED`. 은행은 06:30 · 06:50 에 미리 만들어 둔 것이고(06:50 실행까지 그날 그룹원 전원 — D24), 없으면(준비 실패 · Redis 비움) 요청 때 같은 규칙으로 만든다(D18) | 문제은행 |
| GS7 | **같은 날 같은 은행**: 같은 날 다시 받으면 같은 `missionStartedAt` 과 **같은 문제 목록(같은 순서)** 을 준다. 동시에 처음 요청해도 은행은 하나다 | 문제은행 |
| GS8 | **오늘 낸 문제는 빼지 않는다**: 같은 날 재요청의 은행에는 오늘 이미 화면에 낸 문제도 그대로 있다 — 클라이언트가 로컬 스토리지 기준으로 빼고 고른다(R-B6 · D18) | 문제은행 |
| GS9 | **진입 신호 판정 순서**(처음 걸리는 것으로 끝): 본문 검증(`INVALID_REQUEST`) → 시간 창(GS2) → `NOT_PARTICIPANT` → `SOLO_GROUP` → `MISSION_NOT_STARTED` → `MISSION_ALREADY_FINISHED` → 없는 PK · 난이도 ≠ 번호(`QUESTION_MISMATCH`) → 번호 ≠ 진행도 + 1(`QUESTION_OUT_OF_ORDER`) → 지금 문제 비교(GS10 · GS11) | 진입 |
| GS10 | **진입 신호 멱등**: 그 번호에 지금 문제가 없으면 이 문제로 고정하고 `200`. 이미 같은 문제가 고정돼 있으면 아무것도 바꾸지 않고 같은 `200`(재접속 · 재시도). 진행도가 오른 뒤의 같은 요청은 GS9 에 따라 `QUESTION_OUT_OF_ORDER` 다 | 진입 |
| GS11 | **번호마다 문제 하나**: 그 번호에 다른 문제가 고정돼 있으면 `QUESTION_MISMATCH`. 지금 문제를 바꾸는 길은 패스(solve-signal)뿐이고, 패스 뒤 교체 문제로 다시 진입 신호를 보낸다. 패스로 넘긴 문제는 그 번호에 다시 고정하지 않는다 — 패스 앞에 보낸 진입 신호의 늦은 재시도가 넘긴 문제를 되살리면 교체 문제가 막히기 때문이다(`QUESTION_MISMATCH`) | 진입 |
| GS12 | **출제 이력 중복은 거부하지 않는다**: 평생 이력에 이미 있는 문제로 진입 신호가 와도 GS9 ~ GS11 만 보고 `200` 을 준다(D15). 서버는 운영 로그로만 남긴다(사용자에게 보이지 않는다) | 진입 |
| GS13 | **진행도 표기**: 응답의 `progress` 는 0~3 이다(내부 -1 은 0). 진입 신호 `200` 의 `progress` 는 언제나 `questionNo − 1` | 진입 |
| GS14 | **문제은행 판정 순서**: 시간 창(GS2) → `NOT_PARTICIPANT` → `SOLO_GROUP` → 미션 시작(GS5) → 은행(GS6 · `QUESTIONS_EXHAUSTED`). 이미 완료(`SUCCEEDED` · `CHEAT_FAILED`)한 유저도 시간 창 안이면 `200` 으로 같은 은행을 받는다(재접속) | 문제은행 |
| GS15 | **늦은 그룹원 표기**: 게임 정보의 `group.laterMembers` = 소속 그룹 전체에서 그 게임 날짜의 그날 그룹원(GS3 — 07:00 전에는 지금까지의 06:50 전 가입자, 07:00 부터는 확정 명단)을 뺀 사람이다. 표기만을 위한 칸이라 문제은행 · 진입 · 풀이 · 실시간 보드 · 결과 · 랭킹은 이 칸을 보지 않고 그날 명단만 쓴다(그날 그룹원 수 · 1명 그룹 판정 · 진행도 모두 `members` 기준). 다음 게임 날짜가 되면 그날 그룹원으로 옮겨 간다 | 게임 정보 |
