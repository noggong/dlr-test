# SPEC — solve-signal

> 판 v2 · 2026-11-11 (v2: T-SS-1 리뷰 반영 — SS2 재시도 본문의 `groupStatus` 는 SS14 의 지금 값) · 짝 문서: [HLD](../hld/solve-signal.md) · [LLD](../lld/solve-signal.md) · [SPEC](../spec/solve-signal.md) · [ORACLE](../oracle/solve-signal.md) · 근거: [PRD](../prd.md) v10 FR-4 · §5.4 ~ §5.7 · NFR-1 ~ NFR-3 · D1 · D9 · D14 · D17 · 공통 규약: [API 가이드](../api-guide.md)

**세 줄 요약**
- 엔드포인트 3개: 풀이 신호 `POST /api/v1/games/today/solves` · 패스 신호 `…/passes` · 부정 실패 신호 `…/failures`. 모두 쿠키 `access` 필수.
- 모두 멱등이다 — 이미 반영된 신호를 다시 보내면(마감 뒤 · 그룹 결과 확정 뒤라도) 같은 `200` 을 받는다. 그래서 네트워크 오류 · 타임아웃이면 같은 본문으로 재시도하면 된다.
- 풀이는 07:00 ~ 08:00 도착, 그리고 08:00:00 ~ 08:00:03 도착 중 `clickedAt` 이 08:00 전인 것만 받는다(D1). 패스는 08:00 전만, 실패 신호는 08:00 이후 무시(지금 상태로 `200`).

## 1. 엔드포인트
| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/v1/games/today/solves` | 풀이 신호 — 푼 번호 · 문제를 보내 진행도 +1 |
| `POST` | `/api/v1/games/today/passes` | 패스 신호 — 지금 문제를 넘긴다(하루 1번) |
| `POST` | `/api/v1/games/today/failures` | 부정 실패 신호 — 로컬 스토리지가 빈 것을 알린다(R-F2) |

공통
- 인증: 쿠키 `access`(JWT 1시간). 없거나 만료 · 위조면 `401 UNAUTHORIZED`.
- 요청 헤더: `Content-Type: application/json`(`failures` 는 본문 없음 — 헤더도 필요 없다). 쿼리 없음.
- 오류 본문: `{ "code": string(ErrorCode), "message": string }`.
- 시각: ISO-8601, 오프셋 필수(api-guide §1). 서버는 `+09:00` 으로 보낸다.

### 1.1 공통 응답 형식
**SolveResponse** (풀이 `200`) · **FailureResponse** (실패 `200`) — 같은 형식
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `progress` | integer | 예 | 0 ~ 3 | 반영 뒤(또는 지금) 내 진행도. 시작 전(내부 -1)은 0 |
| `missionStatus` | string (MemberStatus) | 예 | `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 내 그룹원 상태 — 본인 기준이라 부정 실패면 `CHEAT_FAILED` 그대로 |
| `groupStatus` | string (GroupStatus) | 예 | `IN_PROGRESS` · `PENDING` · `SUCCEEDED` · `FAILED` | 그룹 상태 — SS14 |

**PassResponse** (패스 `200`)
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `passedAtQuestionNo` | integer | 예 | 1 · 2 · 3 | 오늘 패스를 쓴 번호(서버 저장 값) |
| `questionNo` | integer | 예 | 1 · 2 · 3 | 교체 문제로 진입 신호(`POST /games/today/shown`)를 보낼 번호 — 패스한 번호와 같다 |
| `progress` | integer | 예 | 0 ~ 3 | 지금 진행도(패스는 진행도를 바꾸지 않는다) |

**ErrorResponse** (모든 오류)
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `code` | string (ErrorCode) | 예 | §3 표의 값 | 클라이언트는 이것으로만 분기 |
| `message` | string | 예 | — | 사람용 문장 |

## 2. 요청 · 응답

### `POST /api/v1/games/today/solves`
- 인증: 쿠키 `access` 필수.
- 설명: 클라이언트가 정답을 맞힌 뒤(정답 판정은 클라이언트 — R-A1) 제출 버튼을 누르면 보낸다. 서버는 SS1 순서로 판정하고, 반영하면 진행도 +1 · 마지막 풀이 도착 시각 = 서버 도착 시각 · 그룹 판정 · 그룹원에게 실시간 전달을 한 번에 한다.

요청 본문
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `questionNo` | integer | 예 | 1 · 2 · 3 | 푼 문제 번호(= 난이도) |
| `questionId` | string | 예 | `^[0-9a-f]{64}$` | 푼 문제 PK — 그 번호의 지금 문제(진입 신호로 보낸 문제)여야 한다 |
| `successTime` | string (date-time) | 예 | ISO-8601 · 오프셋 필수 | 맞힌 시각(클라이언트). 기록만 하고 판정 · 랭킹에 쓰지 않는다(R-S5) |
| `clickedAt` | string (date-time) | 예 | ISO-8601 · 오프셋 필수 | 제출 버튼 클릭 시각(클라이언트). 08:00:00 ~ 08:00:03 도착분의 승인에만 쓴다(D1) |

응답
| 상태 | 본문 | 언제 |
|---|---|---|
| `200` | SolveResponse | 반영함 · 이미 반영된 신호(재시도) |
| `400` · `401` · `403` · `409` | ErrorResponse | §3 |

오류 코드
| code | HTTP | 조건 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 필드 빠짐 · `questionNo` 가 1~3 밖 · `questionId` 형식 위반 · 시각 형식 위반 또는 오프셋 없음 |
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님(07:00 이후 가입) |
| `SOLO_GROUP` | 409 | 그날 그룹원이 1명 |
| `MISSION_NOT_STARTED` | 409 | 문제은행을 받기 전(SS1-1) |
| `MISSION_ALREADY_FINISHED` | 409 | 부정 실패한 뒤 새 번호(SS1-3) |
| `GAME_NOT_OPEN` | 409 | 07:00 전(그날 명단이 아직 없음 — SS15) |
| `SUBMISSION_CLOSED` | 409 | SS3 의 시간 창 밖 |
| `QUESTION_OUT_OF_ORDER` | 409 | `questionNo > 진행도 + 1` |
| `QUESTION_MISMATCH` | 409 | `questionId` ≠ 그 번호의 지금 문제, 또는 진입 신호를 받은 문제가 없음 |

요청 예시
```bash
curl -X POST http://localhost:8080/api/v1/games/today/solves -b 'access=<jwt>' -H 'Content-Type: application/json' -d '{"questionNo":1,"questionId":"9f2c4e1a7b3d5f6081a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708","successTime":"2026-11-12T07:03:10.412+09:00","clickedAt":"2026-11-12T07:03:11.020+09:00"}'
```
```json
{
  "questionNo": 1,
  "questionId": "9f2c4e1a7b3d5f6081a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708",
  "successTime": "2026-11-12T07:03:10.412+09:00",
  "clickedAt": "2026-11-12T07:03:11.020+09:00"
}
```
응답 예시 `200` (반영 · 같은 본문의 재시도도 같다)
```json
{ "progress": 1, "missionStatus": "IN_PROGRESS", "groupStatus": "IN_PROGRESS" }
```
응답 예시 `200` (마지막 그룹원이 3번을 풀어 그룹 성공)
```json
{ "progress": 3, "missionStatus": "SUCCEEDED", "groupStatus": "SUCCEEDED" }
```
응답 예시 `409`
```json
{ "code": "SUBMISSION_CLOSED", "message": "제출이 마감되었습니다." }
```

### `POST /api/v1/games/today/passes`
- 인증: 쿠키 `access` 필수.
- 설명: 패스 버튼을 누른 즉시 보낸다(R-P2). 지금 문제를 넘기고 그 번호의 지금 문제를 비운다 — 클라이언트는 은행에서 같은 난이도의 다른 문제를 골라 `questionNo` 로 진입 신호를 보낸다. 진행도는 바뀌지 않는다. 그룹원 현황판에 「n번에서 패스」가 실시간으로 보인다.

요청 본문
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `questionNo` | integer | 예 | 1 · 2 · 3 | 패스하는 번호 — 진행도 + 1 이어야 한다 |
| `questionId` | string | 예 | `^[0-9a-f]{64}$` | 넘기는 문제 PK — 그 번호의 지금 문제여야 한다 |

응답
| 상태 | 본문 | 언제 |
|---|---|---|
| `200` | PassResponse | 반영함 · 같은 번호 · 같은 PK 의 재시도 |
| `400` · `401` · `403` · `409` | ErrorResponse | §3 |

오류 코드
| code | HTTP | 조건 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 필드 빠짐 · 범위 · 형식 위반 |
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님 |
| `SOLO_GROUP` | 409 | 그날 그룹원이 1명 |
| `MISSION_NOT_STARTED` | 409 | 문제은행을 받기 전 |
| `PASS_ALREADY_USED` | 409 | 오늘 다른 번호 또는 다른 문제로 이미 패스함(R-P5) |
| `MISSION_ALREADY_FINISHED` | 409 | 미션 성공 · 부정 실패 뒤 |
| `GAME_NOT_OPEN` | 409 | 07:00 전 |
| `SUBMISSION_CLOSED` | 409 | 08:00:00 이후 도착(유예 없음) · 그룹 결과 확정 뒤 |
| `QUESTION_OUT_OF_ORDER` | 409 | `questionNo ≠ 진행도 + 1` |
| `QUESTION_MISMATCH` | 409 | `questionId` ≠ 지금 문제, 또는 지금 문제가 없음 |

요청 예시
```bash
curl -X POST http://localhost:8080/api/v1/games/today/passes -b 'access=<jwt>' -H 'Content-Type: application/json' -d '{"questionNo":2,"questionId":"0b7e3c9d1f2a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"}'
```
```json
{ "questionNo": 2, "questionId": "0b7e3c9d1f2a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4" }
```
응답 예시 `200`
```json
{ "passedAtQuestionNo": 2, "questionNo": 2, "progress": 1 }
```
응답 예시 `409`
```json
{ "code": "PASS_ALREADY_USED", "message": "오늘 패스를 이미 썼습니다." }
```

### `POST /api/v1/games/today/failures`
- 인증: 쿠키 `access` 필수.
- 설명: 미션을 시작한 유저의 로컬 스토리지가 비어 있으면 클라이언트가 보낸다(R-F2). 서버는 그 유저를 `CHEAT_FAILED` 로 바꾸고(진행도는 그대로 — R-F4) 그룹 판정을 한다. 다른 그룹원에게는 그룹 결과 확정 전까지 「진행 중」으로 보인다(D2 — realtime-board).

요청: 본문 없음(보내도 무시한다). 쿼리 · 추가 헤더 없음.

응답
| 상태 | 본문 | 언제 |
|---|---|---|
| `200` | FailureResponse | 반영함 · 이미 부정 실패(재시도) · 미션 성공 뒤(무시 — D9) · 08:00 이후 · 그룹 결과 확정 뒤(무시) |
| `401` · `403` · `409` | ErrorResponse | §3 |

오류 코드
| code | HTTP | 조건 |
|---|---|---|
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님 |
| `SOLO_GROUP` | 409 | 그날 그룹원이 1명 |
| `MISSION_NOT_STARTED` | 409 | 문제은행을 받기 전(07:00 전 포함 — D14) |

요청 예시
```bash
curl -X POST http://localhost:8080/api/v1/games/today/failures -b 'access=<jwt>'
```
응답 예시 `200` (반영)
```json
{ "progress": 1, "missionStatus": "CHEAT_FAILED", "groupStatus": "IN_PROGRESS" }
```
응답 예시 `200` (미션 성공 뒤 — 무시)
```json
{ "progress": 3, "missionStatus": "SUCCEEDED", "groupStatus": "IN_PROGRESS" }
```
응답 예시 `409`
```json
{ "code": "MISSION_NOT_STARTED", "message": "미션을 시작하지 않았습니다." }
```

## 3. 오류
| 코드 | HTTP | 언제 | solves | passes | failures |
|---|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | 본문 형식 · 필드 규칙 위반 | ○ | ○ | — |
| `UNAUTHORIZED` | 401 | `access` 없음 · 만료 · 위조 | ○ | ○ | ○ |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님 | ○ | ○ | ○ |
| `SOLO_GROUP` | 409 | 그날 그룹원 1명 | ○ | ○ | ○ |
| `MISSION_NOT_STARTED` | 409 | 미션 시작 전 | ○ | ○ | ○ |
| `MISSION_ALREADY_FINISHED` | 409 | 완료 뒤 새 신호 | ○ (부정 실패 뒤) | ○ (성공 · 부정 실패 뒤) | — |
| `GAME_NOT_OPEN` | 409 | 07:00 전 | ○ | ○ | — |
| `SUBMISSION_CLOSED` | 409 | 시간 창 밖(SS3 · SS10) | ○ | ○ | — |
| `QUESTION_OUT_OF_ORDER` | 409 | 번호 순서 위반 | ○ | ○ | — |
| `QUESTION_MISMATCH` | 409 | 지금 문제와 다름 · 지금 문제 없음 | ○ | ○ | — |
| `PASS_ALREADY_USED` | 409 | 오늘 패스 이미 씀 | — | ○ | — |
- 여러 조건에 걸리면 SS1 · SS8 · SS11 의 순서에서 먼저 걸린 것 하나만 준다. 인증(401) → 본문 검증(400) → 참가(403 · 409 SOLO_GROUP) → 판정 순서.

## 4. 규칙
「도착 시각 `t`」 = 신호가 Redis 에 도착한 서버 시각(Lua 안 `TIME` — 앱 서버 시계 · 클라이언트 시각을 쓰지 않는다).

| id | 규칙 | 근거 |
|---|---|---|
| SS1 | **풀이 판정 순서** — 위에서부터 보고 처음 걸린 것으로 끝낸다: ① 시작 전 → `MISSION_NOT_STARTED` ② `questionNo ≤ 진행도` → 반영 없이 `200`(SS2) ③ 부정 실패 → `MISSION_ALREADY_FINISHED` ④ 시간 창(SS3) ⑤ `questionNo > 진행도 + 1` → `QUESTION_OUT_OF_ORDER` ⑥ 지금 문제 불일치 · 없음 → `QUESTION_MISMATCH` ⑦ 반영 | HLD §3 · §4 |
| SS2 | **풀이 멱등** — 같은 (날짜 · 유저 · 번호)는 처음 한 번만 반영한다. 이미 반영된 번호는 시간 창 · 그룹 결과와 상관없이 `200` + 지금 진행도 · 상태(재시도마다 같은 본문 — 다만 `groupStatus` 는 SS14 의 지금 값이라, 그사이 08:00 이 지나거나 결과가 나면 `PENDING` · `SUCCEEDED` · `FAILED` 로 달라질 수 있다). 그룹원에게 다시 알리지 않는다 | FR-4 · R-S3 · R-S4 · NFR-3 |
| SS3 | **풀이 시간 창(D1)** — `t < 07:00:00` → `GAME_NOT_OPEN` · `07:00:00 ≤ t < 08:00:00` → 통과 · `08:00:00 ≤ t < 08:00:03` → `clickedAt < 08:00:00` 일 때만 통과, 아니면 `SUBMISSION_CLOSED` · `t ≥ 08:00:03` → `SUBMISSION_CLOSED` | D1 · R-D2 · NFR-1 |
| SS4 | **확정 그룹의 유예 풀이(D17)** — 08:00 에 결과가 확정된 그룹(내 상태 `FAILED`)도 SS3 을 통과하면 진행도 · 마지막 풀이 도착 시각 · 랭킹에 반영한다. 상태(`FAILED`)와 그룹 결과는 바뀌지 않는다 | D17 · R-D5 |
| SS5 | **순서 · 지금 문제** — 반영되는 번호는 진행도 + 1 뿐이다. `questionId` 는 그 번호의 진입 신호로 고정된 지금 문제여야 한다 | R-S4 · D6 |
| SS6 | **반영** — 진행도 +1 · 마지막 풀이 도착 시각 = `t` · 지금 문제 비우기. 진행도 3 이고 상태가 `IN_PROGRESS` 면 `SUCCEEDED` | R-S5 · §4.1 |
| SS7 | **그룹 판정** — 그날 그룹원 전원이 완료(`SUCCEEDED` · `CHEAT_FAILED`)가 되는 신호에서만 결과가 정해진다: 전원 `SUCCEEDED` → `SUCCEEDED`, 그 밖 → `FAILED`. 결과는 그룹마다 한 번만 기록 · 전달한다 | §4.2 · R-F6 · NFR-2 |
| SS8 | **패스 판정 순서** — ① 시작 전 → `MISSION_NOT_STARTED` ② 같은 번호 · 같은 PK 로 이미 패스 → `200`(SS9) · 다른 패스를 이미 씀 → `PASS_ALREADY_USED` ③ 성공 · 부정 실패 → `MISSION_ALREADY_FINISHED` ④ 시간 창(SS10) ⑤ `questionNo ≠ 진행도 + 1` → `QUESTION_OUT_OF_ORDER` · 지금 문제 불일치 · 없음 → `QUESTION_MISMATCH` ⑥ 반영 | HLD §3 |
| SS9 | **패스 멱등 · 하루 1번** — 같은 번호 · 같은 PK 의 재시도는 마감 뒤에 와도 같은 `200`. 그 밖의 두 번째 패스는 거부 | R-P1 · R-P5 |
| SS10 | **패스 시간 창** — `07:00:00 ≤ t < 08:00:00` 이고 그룹 결과가 없을 때만. 유예(D1)는 없다 | HLD §5 |
| SS11 | **실패 판정** — ① 시작 전 → `MISSION_NOT_STARTED` ② 미션 성공(D9) · 이미 부정 실패 · 마감 실패(`FAILED`) · `t ≥ 08:00:00` · 그룹 결과 확정 뒤 → 바꾸지 않고 `200` + 지금 상태 ③ 반영: `CHEAT_FAILED`, 진행도 그대로, 그룹 판정(SS7) | R-F2 · R-F4 · R-F6 · D9 · D14 · D17 |
| SS12 | **랭킹 시각** — 랭킹의 「푼 시각」은 `t` 다. `successTime` 은 기록만 하고 어디에도 쓰지 않는다. `clickedAt` 은 SS3 의 유예 구간 판정에만 쓴다 | R-S5 · R-D4 |
| SS13 | **응답 값** — `progress` 는 0~3(내부 -1 → 0). `missionStatus` 는 본인 기준 상태(부정 실패 본인에게는 `CHEAT_FAILED` — R-F5) | api-guide §1 · §2 |
| SS14 | **응답의 그룹 상태** — 그룹 결과 `SUCCEEDED` → `SUCCEEDED` · 즉시 실패 · 마감 실패 → `FAILED` · 결과가 없고 08:00 판정이 미뤄졌거나 `t ≥ 08:00:00` → `PENDING` · 그 밖 `IN_PROGRESS` | api-guide §2 · deadline-result HLD §3 |
| SS15 | **참가 · 명단** — 그날 명단(07:00 확정)이 없을 때: 07:00 전이면 풀이 · 패스 `GAME_NOT_OPEN`, 실패 `MISSION_NOT_STARTED`. 07:00 이후면 07:00 이후 가입자 `NOT_PARTICIPANT` · 1명 그룹 `SOLO_GROUP` · 그 밖 `MISSION_NOT_STARTED` | R-G4 ~ R-G6 · D14 |
| SS16 | **실시간 전달** — 반영된 신호(풀이 · 패스 · 실패)와 그룹 결과는 같은 처리 안에서 그룹 채널로 전달된다. 거부 · 재시도(`200` 멱등)는 전달하지 않는다. 메시지 형식은 realtime-board SPEC | R-S6 · R-R2 · R-R3 |
