# SPEC — realtime-board

> 판 v1.2 · 2026-11-11 (v1.2: T-RB-7 리뷰 반영 — 스트림 `409 GAME_NOT_OPEN` 은 그날 명단이 없을 때도 난다(`board_read` stream 모드) · `REALTIME_TICKET_INVALID` 조건을 표마다 같게 · v1.1: T-RB-7 계약 반영 — 서버 사정(그룹 채널 구독 끊김)으로 `rankingFinal` 없이 스트림이 정상 종료될 수 있고, 클라이언트는 RB7 대로 새 티켓으로 다시 붙는다 · `ticket` 없음도 `401`(`400` 이 아님)) · 짝 문서: [HLD](../hld/realtime-board.md) · [LLD](../lld/realtime-board.md) · [SPEC](../spec/realtime-board.md) · [ORACLE](../oracle/realtime-board.md) · 근거: [PRD](../prd.md) v10 FR-5 · §5.8 · R-R1 ~ R-R5 · R-F4 · R-F5 · D2 · D5 · D13 · D17 · 공통 규약: [API 가이드](../api-guide.md) §1 · §2 · §4

**세 줄 요약**
- 엔드포인트 3개: 현황판 조회 `GET /api/v1/games/today/board`(07:00 ~ 08:00) · 티켓 발급 `POST /api/v1/realtime/tickets`(07:00 ~ 08:00:03, 30초 1회용) · SSE `GET /realtime/v1/games/today/events?ticket=`. 실시간 이벤트 5종(`board.snapshot` · `member.progressed` · `group.pending` · `group.result` · `group.ranking_final`) + 하트비트.
- 모든 이벤트 `data` 에 `version`(그룹 Hash 버전)이 있고 `id:` 와 같다. 클라이언트는 한 연결 안에서 받은 `version` 이하를 버린다(RB6). 놓친 이벤트는 다시 보내지 않는다 — 재연결은 새 티켓으로 하고 첫 메시지 `board.snapshot` 이 지금 상태다(RB7).
- 오류 코드: `UNAUTHORIZED` · `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP` · `REALTIME_TICKET_INVALID`. 보는 사람별 상태 규칙(부정 실패 표시 — D2)은 RB10.

## 1. 엔드포인트
| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/api/v1/games/today/board` | 쿠키 `access` | 오늘 내 그룹 현황판(보는 사람 기준) |
| `POST` | `/api/v1/realtime/tickets` | 쿠키 `access` | SSE 연결용 30초 1회용 티켓 |
| `GET` | `/realtime/v1/games/today/events?ticket=` | 쿼리 `ticket` | SSE 스트림 — 실시간 이벤트(§6) |

## 2. 공통 타입
**MemberEntry** — 현황판 · 결과 랭킹 · SSE 의 한 줄(결과 조회 deadline-result 와 같은 타입)
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `userId` | string | 예 | `^[a-z0-9_]{4,20}$` | 그룹원 로그인 아이디(D5) |
| `rank` | integer | 예 | ≥ 1 | 그룹 내 순위 — 경쟁 순위(1, 1, 3). 부정 실패는 결과 확정 전에도 맨 아래(D2). 보는 사람과 상관없이 같다 |
| `progress` | integer | 예 | 0 ~ 3 | 푼 문제 수. 미션 시작 전(내부 -1)은 0(R-R1) |
| `status` | MemberStatus | 예 | 아래 5개 | 보는 사람별 규칙 RB10 |
| `started` | boolean | 예 | — | 미션을 시작했는가. `false` 면 「미접속」(D13) |
| `lastSolvedAt` | string(date-time) · null | 예 | ISO-8601 `+09:00`, 마이크로초까지 | 마지막 풀이 신호의 서버 도착 시각. 푼 문제가 없으면 `null`(D5) |
| `passedAtQuestionNo` | integer · null | 예 | 1 · 2 · 3 · null | 패스를 쓴 문제 번호. 안 썼으면 `null`(D5) |

**MemberStatus**: `NOT_STARTED`(미접속) · `IN_PROGRESS`(진행 중) · `SUCCEEDED`(성공) · `CHEAT_FAILED`(부정 실패 — 실패) · `FAILED`(마감 실패 — 실패). 뜻은 API 가이드 §2.

**GroupStatus**: `IN_PROGRESS`(게임 중) · `PENDING`(08:00 이후 결과 확정 대기 — 「로딩 중」) · `SUCCEEDED` · `FAILED`.

`entries` 배열은 언제나 그날 그룹원 **전원**(2 ~ 10명)이고 `rank` 오름차순, 같은 `rank` 안에서는 `userId` 사전순이다(RB11).

## 3. `GET /api/v1/games/today/board`
- **인증**: 쿠키 `access`. **설명**: 오늘 게임 중(07:00 ~ 08:00) 내 그룹의 지금 현황판. 08:00 이후 화면은 결과 조회(`GET /api/v1/games/results/latest`)를 쓴다. 미션 시작 전에도 준다(본인 줄 「미접속」). SSE `board.snapshot` 과 같은 함수로 만든다(RB12).

**요청**
| 위치 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 헤더 | `Cookie` | string | 예 | `access=<JWT>` | 로그인 쿠키(브라우저가 자동으로 싣는다) |
| 쿼리 · 본문 | — | — | — | — | 없음 |

**응답 `200`** `application/json`
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `date` | string(date) | 예 | `YYYY-MM-DD` | 게임 날짜(KST) |
| `groupStatus` | GroupStatus | 예 | `IN_PROGRESS` · `SUCCEEDED` · `FAILED` (`PENDING` 은 08:00 이후라 이 응답에 오지 않는다) | 그룹 상태 |
| `version` | integer | 예 | ≥ 0 | 그룹 Hash 버전 — SSE `version` 과 같은 축(RB6) |
| `entries` | MemberEntry[] | 예 | 2 ~ 10개 | 현황판(§2) |

**오류**
| HTTP | code | 조건 |
|---|---|---|
| 401 | `UNAUTHORIZED` | `access` 없음 · 만료 · 위조 |
| 409 | `GAME_NOT_OPEN` | Redis 시각 `< 07:00:00` 또는 `≥ 08:00:00` · 그룹의 그날 명단이 아직 없음 · 그룹 상태가 아직 없음(RB1 · RB3) |
| 403 | `NOT_PARTICIPANT` | 그날 그룹원이 아님(07:00 이후 가입) |
| 409 | `SOLO_GROUP` | 그날 그룹원이 1명 |

**요청 예시**
```
curl -s -b 'access=eyJhbGciOi...' http://localhost:8080/api/v1/games/today/board
```

**응답 예시** (`minji` 가 보는 화면. `hana` 는 부정 실패라 다른 그룹원에게 `IN_PROGRESS` · 맨 아래 — RB10)
```json
{
  "date": "2026-11-12",
  "groupStatus": "IN_PROGRESS",
  "version": 17,
  "entries": [
    { "userId": "junho",  "rank": 1, "progress": 2, "status": "IN_PROGRESS", "started": true,  "lastSolvedAt": "2026-11-12T07:03:41.120384+09:00", "passedAtQuestionNo": null },
    { "userId": "minji",  "rank": 2, "progress": 2, "status": "IN_PROGRESS", "started": true,  "lastSolvedAt": "2026-11-12T07:04:02.553001+09:00", "passedAtQuestionNo": 1 },
    { "userId": "dohyun", "rank": 3, "progress": 0, "status": "NOT_STARTED", "started": false, "lastSolvedAt": null, "passedAtQuestionNo": null },
    { "userId": "hana",   "rank": 4, "progress": 1, "status": "IN_PROGRESS", "started": true,  "lastSolvedAt": "2026-11-12T07:02:10.000250+09:00", "passedAtQuestionNo": null }
  ]
}
```
오류 예시 `409`
```json
{ "code": "GAME_NOT_OPEN", "message": "게임 시간이 아닙니다." }
```

## 4. `POST /api/v1/realtime/tickets`
- **인증**: 쿠키 `access`. **설명**: SSE 연결용 1회용 티켓을 준다. `EventSource` 는 헤더 · 쿠키를 다른 출처로 싣지 못하므로 이 티켓을 쿼리로 넘긴다. 07:00 ~ 08:00:03 에만 발급한다 — 08:00 ~ 08:00:03 은 미룬 그룹의 결과 메시지를 받기 위해 연다. 미션 시작 전에도 발급한다. 연결할 때마다 새로 받는다(RB4).

**요청**
| 위치 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 헤더 | `Cookie` | string | 예 | `access=<JWT>` | 로그인 쿠키 |
| 본문 | — | — | — | 비어 있음(보내도 무시) | — |

**응답 `201`** `application/json`
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `ticket` | string | 예 | `^[A-Za-z0-9_-]{43}$` (32바이트 base64url) | SSE `?ticket=` 에 그대로 넣는다 |
| `expiresAt` | string(date-time) | 예 | 발급 시각(Redis) + 30초 | 이 시각까지 연결을 열어야 한다 |

**오류**
| HTTP | code | 조건 |
|---|---|---|
| 401 | `UNAUTHORIZED` | `access` 없음 · 만료 · 위조 |
| 409 | `GAME_NOT_OPEN` | Redis 시각 `< 07:00:00` 또는 `≥ 08:00:03` · 그룹의 그날 명단이 아직 없음(RB2 · RB3) |
| 403 | `NOT_PARTICIPANT` | 그날 그룹원이 아님(07:00 이후 가입) |
| 409 | `SOLO_GROUP` | 그날 그룹원이 1명 |

**요청 예시**
```
curl -s -X POST -b 'access=eyJhbGciOi...' http://localhost:8080/api/v1/realtime/tickets
```

**응답 예시** `201`
```json
{ "ticket": "q3V9cX0bS2lQe1o7YfW8nB4tH6kR2mJ5pL0aD9sG1uE", "expiresAt": "2026-11-12T07:30:12.481000+09:00" }
```
오류 예시 `403`
```json
{ "code": "NOT_PARTICIPANT", "message": "오늘 게임의 그룹원이 아닙니다." }
```

## 5. `GET /realtime/v1/games/today/events?ticket=`
- **인증**: 쿼리 `ticket`(쿠키 · 헤더 인증 없음). **설명**: 티켓을 한 번 쓰고(`GETDEL`) SSE 스트림을 연다. 첫 메시지는 언제나 `board.snapshot`, 그 뒤로 그룹이 바뀔 때마다 §6 의 이벤트가 온다. 15초마다 하트비트. 서버는 `Last-Event-ID` 를 읽지 않는다(RB14).

**요청**
| 위치 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 쿼리 | `ticket` | string | 예 | `^[A-Za-z0-9_-]{43}$` · 발급 뒤 30초 안 · 처음 쓰는 것 | `POST /api/v1/realtime/tickets` 의 `ticket`. 없어도 `400` 이 아니라 `401 REALTIME_TICKET_INVALID` |
| 헤더 | `Accept` | string | 아니오 | `text/event-stream` | — |
| 헤더 | `Last-Event-ID` | string | 아니오 | — | 무시한다 |

**응답 `200`** `text/event-stream; charset=utf-8` · `Cache-Control: no-cache` · `X-Accel-Buffering: no`
| 프레임 줄 | 값 | 설명 |
|---|---|---|
| `id:` | 정수 문자열 | 그 이벤트 `data.version` 과 같다(RB15) |
| `event:` | §6 의 이벤트 이름 5개 | — |
| `data:` | JSON 한 줄 | §6 의 이벤트별 표 |
| `: ping` | 주석 줄 | 하트비트 15초(RB9) |

스트림 끝: 서버는 `rankingFinal = true` 인 메시지(`group.result` 또는 `group.ranking_final`)를 보낸 직후, 또는 연결 때 이미 랭킹이 확정된 그룹이면 `board.snapshot` 한 개 뒤에 스트림을 정상 종료한다(RB8).
서버 쪽 그룹 채널 구독이 끊겨도 스트림은 `rankingFinal` 메시지 없이 정상 종료된다 — 끝난 스트림이 아니므로 클라이언트는 RB7 대로 새 티켓으로 다시 붙는다(RB8).

**오류** (스트림을 열기 전 — `application/json` 본문)
| HTTP | code | 조건 |
|---|---|---|
| 401 | `REALTIME_TICKET_INVALID` | `ticket` 없음 · 모양 틀림 · 없는 티켓 · 30초 지남 · 이미 씀 |
| 409 | `GAME_NOT_OPEN` | 티켓은 맞지만 그날 명단 · 그룹 상태(Redis)가 없음 — 재적재 전 · 키 만료 등 |

**요청 예시**
```
curl -N -H 'Accept: text/event-stream' 'http://localhost:8081/realtime/v1/games/today/events?ticket=q3V9cX0bS2lQe1o7YfW8nB4tH6kR2mJ5pL0aD9sG1uE'
```

**응답 예시** (연결 직후 → 1분 뒤 진행 변화 → 하트비트)
```
id: 17
event: board.snapshot
data: {"version":17,"date":"2026-11-12","groupStatus":"IN_PROGRESS","entries":[...],"sentAt":"2026-11-12T07:30:00.910233+09:00"}

id: 18
event: member.progressed
data: {"version":18,"date":"2026-11-12","groupStatus":"IN_PROGRESS","userId":"dohyun","entries":[...],"occurredAt":"2026-11-12T07:31:02.004118+09:00"}

: ping

```
오류 예시 `401`
```json
{ "code": "REALTIME_TICKET_INVALID", "message": "실시간 연결 티켓이 없거나 만료되었습니다." }
```

## 6. 실시간 이벤트
모든 `data` 는 JSON 한 줄이고 `version` 을 가진다. `id:` = `data.version`. 아래 예시의 `entries` 는 4명 그룹(`junho` · `minji` · `dohyun` · `hana`)을 `minji` 가 보는 값이다.

### 6.1 `board.snapshot`
- **언제**: 연결마다 **첫 메시지로 한 번**. 그 순간의 그룹 Hash 로 만든 보는 사람 기준 현황판 — 재연결 때 놓친 이벤트를 대신한다(R-R4 · S15 · S16).

| key | 타입 | enum 요소 | 설명 |
|---|---|---|---|
| `version` | integer | — | 그룹 Hash 버전 |
| `date` | string(date) | — | 게임 날짜 |
| `groupStatus` | GroupStatus | `IN_PROGRESS` · `PENDING` · `SUCCEEDED` · `FAILED` | 지금 그룹 상태. `SUCCEEDED` · `FAILED` 면 결과가 이미 확정됐다(랭킹 · `rankingFinal` 은 결과 조회로 — RB13) |
| `entries` | MemberEntry[] | `status`: `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 현황판 전체(§2 · RB10 · RB11) |
| `sentAt` | string(date-time) | — | 서버가 이 메시지를 만든 시각 |

```
id: 17
event: board.snapshot
data: {"version":17,"date":"2026-11-12","groupStatus":"IN_PROGRESS","entries":[{"userId":"junho","rank":1,"progress":2,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:03:41.120384+09:00","passedAtQuestionNo":null},{"userId":"minji","rank":2,"progress":2,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:04:02.553001+09:00","passedAtQuestionNo":1},{"userId":"dohyun","rank":3,"progress":0,"status":"NOT_STARTED","started":false,"lastSolvedAt":null,"passedAtQuestionNo":null},{"userId":"hana","rank":4,"progress":1,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:02:10.000250+09:00","passedAtQuestionNo":null}],"sentAt":"2026-11-12T07:30:00.910233+09:00"}

```

### 6.2 `member.progressed`
- **언제**: 그룹 결과가 **아직 없을 때** 그룹원 한 명의 줄이 바뀐 순간 — 미션 시작(첫 문제은행), 풀이 반영, 패스, 부정 실패, 미룬 그룹(`PENDING`)의 유예 풀이. 순위가 함께 바뀌므로 바뀐 뒤 현황판 전체를 싣는다(R-R2 · S3 · S4 · S9).
- 부정 실패도 이 이벤트다 — 다른 그룹원에게는 그 줄이 `IN_PROGRESS` · 맨 아래로, 본인에게는 `CHEAT_FAILED` 로 간다(RB10).

| key | 타입 | enum 요소 | 설명 |
|---|---|---|---|
| `version` | integer | — | 그룹 Hash 버전 |
| `date` | string(date) | — | 게임 날짜 |
| `groupStatus` | GroupStatus | `IN_PROGRESS` · `PENDING` | 결과가 없는 그룹이라 이 둘뿐 |
| `userId` | string | — | 바뀐 그룹원(토스트 표시용) |
| `entries` | MemberEntry[] | `status`: `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED`(본인 줄만) | 바뀐 뒤 현황판 전체 |
| `occurredAt` | string(date-time) | — | 변경이 반영된 Redis 시각 |

```
id: 18
event: member.progressed
data: {"version":18,"date":"2026-11-12","groupStatus":"IN_PROGRESS","userId":"dohyun","entries":[{"userId":"junho","rank":1,"progress":2,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:03:41.120384+09:00","passedAtQuestionNo":null},{"userId":"minji","rank":2,"progress":2,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:04:02.553001+09:00","passedAtQuestionNo":1},{"userId":"dohyun","rank":3,"progress":0,"status":"IN_PROGRESS","started":true,"lastSolvedAt":null,"passedAtQuestionNo":null},{"userId":"hana","rank":4,"progress":1,"status":"IN_PROGRESS","started":true,"lastSolvedAt":"2026-11-12T07:02:10.000250+09:00","passedAtQuestionNo":null}],"occurredAt":"2026-11-12T07:31:02.004118+09:00"}

```

### 6.3 `group.pending`
- **언제**: 08:00 마감 1차에서 그룹 확정이 08:00:03 까지 미뤄진 순간(3번 문제를 푸는 중인 그룹원이 있음 — D17). 화면은 「로딩 중」으로 바꾸고 `group.result` 를 기다린다. 현황판은 싣지 않는다.

| key | 타입 | enum 요소 | 설명 |
|---|---|---|---|
| `version` | integer | — | 그룹 Hash 버전 |
| `date` | string(date) | — | 게임 날짜 |
| `groupStatus` | GroupStatus | `PENDING` 만 | — |
| `occurredAt` | string(date-time) | — | 미룬 Redis 시각 |

```
id: 31
event: group.pending
data: {"version":31,"date":"2026-11-12","groupStatus":"PENDING","occurredAt":"2026-11-12T08:00:00.012004+09:00"}

```

### 6.4 `group.result`
- **언제**: (1) 그룹 결과가 확정된 순간 — 성공 · 즉시 실패(08:00 전, 또는 미룬 그룹의 유예 풀이로) · 마감 실패(08:00 1차 · 08:00:03 2차)(R-R3 · S5 · S10). (2) 08:00 에 확정된 그룹에 유예 풀이(08:00:00 ~ 08:00:03 도착 · 클릭 08:00 전)가 반영돼 잠정 랭킹이 바뀐 순간 — 같은 이벤트를 새 `version` 으로 다시 보낸다(R-D5).
- 결과 확정 뒤라 모든 그룹원이 같은 `entries` 를 받는다(부정 실패는 모두에게 `CHEAT_FAILED`).

| key | 타입 | enum 요소 | 설명 |
|---|---|---|---|
| `version` | integer | — | 그룹 Hash 버전 |
| `date` | string(date) | — | 게임 날짜 |
| `groupStatus` | GroupStatus | `SUCCEEDED` · `FAILED` | 그룹 결과 |
| `decidedAt` | string(date-time) | — | 결과가 확정된 Redis 시각 |
| `rankingFinal` | boolean | — | 랭킹 확정 여부. 08:00 전 확정(전원 완료)은 `true`, 08:00 이후 확정은 08:00:03 까지 `false`(잠정) |
| `entries` | MemberEntry[] | `status`: `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 그룹 내 랭킹(결과 조회 `ranking` 과 같은 값 — NFR-4) |

```
id: 26
event: group.result
data: {"version":26,"date":"2026-11-12","groupStatus":"FAILED","decidedAt":"2026-11-12T07:48:15.302117+09:00","rankingFinal":true,"entries":[{"userId":"junho","rank":1,"progress":3,"status":"SUCCEEDED","started":true,"lastSolvedAt":"2026-11-12T07:21:09.880112+09:00","passedAtQuestionNo":null},{"userId":"minji","rank":2,"progress":3,"status":"SUCCEEDED","started":true,"lastSolvedAt":"2026-11-12T07:40:55.100020+09:00","passedAtQuestionNo":1},{"userId":"dohyun","rank":3,"progress":3,"status":"SUCCEEDED","started":true,"lastSolvedAt":"2026-11-12T07:48:15.302117+09:00","passedAtQuestionNo":null},{"userId":"hana","rank":4,"progress":1,"status":"CHEAT_FAILED","started":true,"lastSolvedAt":"2026-11-12T07:02:10.000250+09:00","passedAtQuestionNo":null}]}

```

### 6.5 `group.ranking_final`
- **언제**: 08:00:03 마감 2차에서 그룹 랭킹이 확정된 순간(D17). 이 메시지 뒤 서버가 스트림을 닫는다(RB8).

| key | 타입 | enum 요소 | 설명 |
|---|---|---|---|
| `version` | integer | — | 그룹 Hash 버전 |
| `date` | string(date) | — | 게임 날짜 |
| `groupStatus` | GroupStatus | `SUCCEEDED` · `FAILED` | 그룹 결과(이미 확정된 값) |
| `decidedAt` | string(date-time) | — | 결과가 확정된 시각(이 메시지 시각이 아님) |
| `rankingFinal` | boolean | `true` 만 | — |
| `entries` | MemberEntry[] | `status`: `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 확정 랭킹 |

```
id: 34
event: group.ranking_final
data: {"version":34,"date":"2026-11-12","groupStatus":"FAILED","decidedAt":"2026-11-12T08:00:00.010551+09:00","rankingFinal":true,"entries":[{"userId":"junho","rank":1,"progress":3,"status":"SUCCEEDED","started":true,"lastSolvedAt":"2026-11-12T07:21:09.880112+09:00","passedAtQuestionNo":null},{"userId":"minji","rank":2,"progress":2,"status":"FAILED","started":true,"lastSolvedAt":"2026-11-12T08:00:01.420387+09:00","passedAtQuestionNo":1},{"userId":"dohyun","rank":3,"progress":0,"status":"FAILED","started":false,"lastSolvedAt":null,"passedAtQuestionNo":null},{"userId":"hana","rank":4,"progress":1,"status":"CHEAT_FAILED","started":true,"lastSolvedAt":"2026-11-12T07:02:10.000250+09:00","passedAtQuestionNo":null}]}

```

### 6.6 하트비트
- **언제**: 마지막 프레임 뒤 15초마다. 주석 줄이라 `EventSource` 는 이벤트로 올리지 않는다. 프록시 idle timeout 을 막고 끊김을 알아채는 데 쓴다(RB9).
```
: ping

```

## 7. 오류 코드 (이 기능 전체)
| code | HTTP | 엔드포인트 | 조건 | 클라이언트 대응 |
|---|---|---|---|---|
| `UNAUTHORIZED` | 401 | board · tickets | `access` 없음 · 만료 · 위조 | `POST /auth/refresh` 후 한 번 재시도 |
| `GAME_NOT_OPEN` | 409 | board · tickets · events | 시간 창 밖(RB1 · RB2) · 그날 명단 없음 · 그룹 상태 없음 | 07:00 전이면 대기 화면, 08:00 이후면 결과 조회. 게임 중이면 문제은행(`POST /api/v1/games/today/question-bank`)을 먼저 부른 뒤 다시 |
| `NOT_PARTICIPANT` | 403 | board · tickets | 그날 그룹원이 아님 | 「내일부터 참가」 · 실시간 연결을 하지 않는다 |
| `SOLO_GROUP` | 409 | board · tickets | 그날 그룹원 1명 | 1명 그룹 안내 · 실시간 연결을 하지 않는다 |
| `REALTIME_TICKET_INVALID` | 401 | events | 티켓 없음 · 모양 틀림 · 없는 티켓 · 만료(30초) · 이미 씀 | 새 티켓을 받아 다시 연결(RB7) |

## 8. 규칙
| id | 규칙 |
|---|---|
| RB1 | **조회 시간 창**: 현황판 조회는 Redis `TIME` 기준 `07:00:00 ≤ t < 08:00:00` 에만 `200`. 밖이면 `409 GAME_NOT_OPEN`. 앱 서버 시계는 판정에 쓰지 않는다 |
| RB2 | **티켓 시간 창**: 티켓 발급은 `07:00:00 ≤ t < 08:00:03` 에만. SSE 연결 자체는 시간 창을 다시 보지 않는다 — 티켓이 유효하면(발급 뒤 30초 안) 연다 |
| RB3 | **판정 순서**(board · tickets 공통, 처음 걸린 것으로 응답): 인증(`UNAUTHORIZED`) → 시간 창(`GAME_NOT_OPEN`) → 그룹의 그날 명단이 아직 없음(`GAME_NOT_OPEN` — 명단은 game-session 이 07:00 에 만든다) → 그날 그룹원 아님(`NOT_PARTICIPANT`) → 그룹원 1명(`SOLO_GROUP`) → (board 만) 그룹 상태 없음(`GAME_NOT_OPEN`). 시각은 모두 Redis `TIME`(+ 서버 시계 오프셋 설정) 기준 |
| RB4 | **티켓**: 1회용 · 발급 뒤 30초. 쓰는 순간 지워진다(`GETDEL`) — 같은 티켓의 두 번째 연결은 동시든 나중이든 `401 REALTIME_TICKET_INVALID`. 한 유저가 여러 장을 받을 수 있고(탭마다), 티켓 하나 = 연결 하나 |
| RB5 | **첫 메시지**: 연결마다 첫 이벤트는 `board.snapshot` 이다. 그 뒤 이벤트는 `version` 이 snapshot 보다 크다 — 서버는 snapshot 을 읽기 전에 그룹 채널을 먼저 구독해 그 사이 변경도 빠뜨리지 않고, snapshot 이하 `version` 은 보내지 않는다 |
| RB6 | **version 비교**: 클라이언트는 **연결마다** `lastVersion` 을 둔다. 새 연결의 `board.snapshot` 은 비교 없이 받고 `lastVersion = data.version`. 그 뒤 이벤트는 `data.version ≤ lastVersion` 이면 버리고, 크면 적용하고 `lastVersion = data.version`. 같은 연결에서 `version` 은 늘기만 한다. 현황판 조회(`GET …/board`) 응답의 `version` 도 같은 축이라 둘을 함께 쓰면 큰 쪽을 남긴다 |
| RB7 | **재연결 절차**(클라이언트): ① `error` 를 받거나 45초 동안 아무 프레임(하트비트 포함)도 없으면 `EventSource.close()` — 브라우저 자동 재연결은 티켓이 1회용이라 401 이 되므로 쓰지 않는다. ② 끝난 스트림(RB8)이었으면 다시 연결하지 않는다. ③ 대기: 첫 재시도는 바로, 그 뒤 1 · 2 · 4 · 8 · 16 · 30초(상한) + 0~1초 무작위. ④ `POST /api/v1/realtime/tickets` → 새 티켓. `401 UNAUTHORIZED` 면 refresh 후 한 번 더. `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP` 이면 재연결을 멈춘다(08:00:03 이후면 결과 조회로 — RB13). ⑤ 새 티켓으로 `EventSource` 를 연다. 연결 실패가 `REALTIME_TICKET_INVALID` 면 ③부터. ⑥ 첫 `board.snapshot` 으로 화면 전체를 다시 그린다(놓친 이벤트를 따로 받지 않는다). 성공적으로 snapshot 을 받으면 대기 단계를 처음으로 되돌린다 |
| RB8 | **스트림 끝**: 서버는 `rankingFinal = true` 인 메시지(`group.result` · `group.ranking_final`)를 보낸 직후 스트림을 정상 종료한다. 연결 때 이미 랭킹이 확정된 그룹이면 `board.snapshot` 하나를 보내고 끝낸다. 클라이언트는 `rankingFinal = true` 를 받았거나 snapshot 의 `groupStatus` 가 `SUCCEEDED` · `FAILED` 면 끝난 스트림으로 보고 다시 연결하지 않는다. 그 밖의 끝(서버의 그룹 채널 구독 끊김 — 정상 종료지만 `rankingFinal` 없음 · 네트워크 끊김)은 끝난 스트림이 아니다 — RB7 로 다시 붙는다 |
| RB9 | **하트비트**: 서버는 마지막 프레임 뒤 15초마다 `: ping`. 클라이언트는 45초(3회) 동안 프레임이 없으면 끊긴 것으로 본다(RB7 ①) |
| RB10 | **보는 사람별 상태**(HLD §3.1 · D2 · D13): 결과 확정 **전** — 부정 실패(`CHEAT_FAILED`) 그룹원은 본인에게 `CHEAT_FAILED`, 다른 그룹원에게 `IN_PROGRESS`(진행도 = 실패 시점 값, 순위 맨 아래). 그 밖 상태(`NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED`)는 모두에게 같다. 결과 확정 **뒤**(`group.result` · `group.ranking_final` · 결과가 있는 snapshot) — 모두에게 같고 `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` 만 나온다. `rank` · `progress` · `started` · `lastSolvedAt` · `passedAtQuestionNo` 는 보는 사람과 상관없이 같다 |
| RB11 | **순위 · 정렬**: `rank` 는 랭킹 규칙(deadline-result HLD §4)의 경쟁 순위. 결과 확정 뒤에는 결과에 저장된 랭킹을 그대로 쓴다(결과 조회와 같은 값). `entries` 는 `rank` 오름차순, 같으면 `userId` 사전순이고 그날 그룹원 전원을 싣는다 |
| RB12 | **조회 = 스트림**: 같은 `version` 에서 같은 유저가 받는 현황판 조회 `entries` 와 SSE `board.snapshot` `entries` 는 같다 |
| RB13 | **08:00 이후**: 현황판 조회는 쓰지 않는다(`GAME_NOT_OPEN`). `group.pending` 을 받으면 「로딩 중」, `group.result` 를 받으면 결과 화면. 08:00:03 이 지나 스트림이 끊겼거나 snapshot 이 `PENDING` · 결과 상태로 왔으면 `GET /api/v1/games/results/latest` 를 쓴다 — `PENDING` 이면 3초 간격으로 다시 부른다 |
| RB14 | **다시 보내기 없음**: 서버는 놓친 이벤트를 다시 보내지 않고 `Last-Event-ID` 요청 헤더를 무시한다. 모든 메시지가 그 시점 상태 전체(`group.pending` 은 그룹 상태)라 다음 메시지 · 재연결 snapshot 이 메운다 |
| RB15 | **`id:`**: 모든 이벤트 프레임의 `id:` 는 `data.version` 과 같은 정수 문자열이다. 하트비트에는 `id:` 가 없다 |
