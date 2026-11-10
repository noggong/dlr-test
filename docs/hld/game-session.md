# HLD — game-session (게임 정보 · 그날 명단 · 미션 시작 · 문제은행 · 진입 신호)

> 판 v3 · 2026-11-11 (v3: 출제 이력 캐시 없앰 · 중복 감지는 worker: persister · v2: 출제 이력 중복은 거부하지 않고 운영 로그로 — prd D15) · 짝 문서: LLD · SPEC · ORACLE (아직 없음) · 근거: [PRD](../prd.md) v7 FR-1 · FR-3 · §5.2 · D6 · D8 · D10 · D14 · D15 · 바탕: [HLD platform](platform.md)

**세 줄 요약**
- 07:00 에 **그날 명단**(07:00 이전 가입자 · 2명 이상 그룹)을 Redis 그룹 Hash 로 올리고, 유저의 **첫 문제은행 요청**이 미션 시작(진행도 -1 → 0)이다. 늦게 요청하면 지각(07:00:03 초과, D8).
- 문제은행은 난이도마다 이 유저가 아직 받지 않은 문제 20개를 무작위로 준다(D10). 문제는 `api` 메모리에, 유저의 출제 이력은 Redis 캐시 + PostgreSQL 정본에 둔다.
- 문제 화면에 들어갈 때마다 오는 **진입 신호**(D6)로 「이 번호의 지금 문제」를 고정하고 출제 이력에 남긴다 — 풀이 · 패스는 이 문제에만 받는다(solve-signal).

## 1. 배경 · 목표
- 맡는 요구: FR-1(자동 참가) · FR-3(게임 정보) · R-G4 ~ R-G6(그날 그룹원 · 1명 그룹) · R-B1 ~ R-B7(미션 시작 · 문제은행 · 평생 한 번) · D6 · D8 · D10 · D14.
- 목표
  - 07:00 몰림(최대 250만 건 / 수 분)에서도 문제은행 요청이 PostgreSQL 을 거치지 않는다.
  - 모든 유저는 같은 문제를 평생 두 번 받지 않는다 — 화면에 나간 문제(진입 신호)까지 포함한다.
  - 미션 시작 · 지각 판정 시각은 서버 시계 하나(Redis `TIME`)로 정한다.
- 목표 아님: 정답 판정(클라이언트) · 문제 선정(클라이언트 무작위) · 문제 등록 도구(시드 데이터로 넣는다).

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `api` GameController | `GET /api/v1/games/today` · `POST …/question-bank` · `POST …/shown`(진입 신호) | GameService |
| GameService (`core`) | 시간 창 판정 · 그날 그룹원 판정 · 문제은행 뽑기 · 진입 신호 검증 | ProgressRepository · QuestionRepository · UserRepository (포트) |
| 문제 캐시 (`api` 메모리) | 난이도별 문제 목록(PK · 본문 · 분야 · 정답 해시). 기동 시 PostgreSQL `questions` 에서 적재 | `infra:rdb` |
| `worker: member` (07:00) | 그날 그룹마다 명단을 Redis 에 올리고 `daily_progress` 에 진행도 -1 행을 넣는다 | `infra:rdb` · `infra:redis` |
| Lua `start` · `shown` | 미션 시작 · 진입 신호를 그룹 Hash 에 원자 반영하고 이벤트를 남긴다 | Redis |

## 3. 흐름
**07:00 명단 확정** (`worker: member`)
1. 그룹마다 그날 그룹원 = 그 날짜 07:00 KST 이전에 가입한 유저(`users.joined_at`).
2. 그룹원이 2명 이상이면 Redis 그룹 Hash(`g:{<b>}:<gid>:<date>`)에 그룹원을 진행도 -1 · 상태 `NOT_STARTED` 로 만들고, 그 그룹을 그날 그룹 목록(`day:{<b>}:<date>` Set)에 넣는다. 1명이면 「1명 그룹」 표시만 남긴다(R-G6).
3. 같은 내용을 `daily_progress` 에 진행도 -1 로 넣는다(LLD platform §3).
- `api` 가 요청을 받았는데 그 그룹 명단이 아직 없으면(잡이 늦음) 같은 규칙으로 그 자리에서 만든다 — Lua 의 「없을 때만 만들기」라 잡과 겹쳐도 한 번만 생긴다.

**게임 정보** `GET /games/today`
- 그날 날짜 · 시작(07:00) · 마감(08:00) · 서버 시각 · 그룹명 · 그날 그룹원 목록을 준다. 07:00 전에는 「지금까지 07:00 이전에 가입한 사람」 목록이다(07:00 에 확정).

**미션 시작 = 문제은행** `POST /games/today/question-bank`
1. 시간 창: 서버 시각이 07:00 전이면 `GAME_NOT_OPEN`(D14), 08:00 이후면 `SUBMISSION_CLOSED`.
2. 그날 그룹원이 아니면(07:00 이후 가입) `NOT_PARTICIPANT`, 1명 그룹이면 `SOLO_GROUP`.
3. Lua `start`: 이 유저가 진행도 -1 이면 0 으로, 상태 `IN_PROGRESS`, 시작 시각 = Redis `TIME`, 지각 = 시작 시각 > 07:00:03(D8). 이벤트(`mission.started`) XADD · 그룹 채널 publish(현황판에서 「미접속」→「진행 중」). 이미 시작했으면 아무것도 바꾸지 않는다(재접속 · 재시도).
4. PostgreSQL `served_questions` 에서 이 유저의 출제 이력(PK `(user_id, question_id)` 인덱스)을 읽어 빼고, 난이도마다 20개 무작위(D10). 한 난이도라도 0개면 `QUESTIONS_EXHAUSTED`. 출제 이력 캐시는 두지 않는다(§5).
5. 응답: 미션 시작 시각 · 문제 목록(PK · 난이도 · 본문 · 분야 · 정답 해시).

**진입 신호** `POST /games/today/shown` (D6) — `{questionNo, questionId}`
1. 시간 창: 07:00 ~ 08:00 만 받는다(밖이면 `GAME_NOT_OPEN` · `SUBMISSION_CLOSED`). 그날 그룹원이 아니면 `NOT_PARTICIPANT`, 1명 그룹이면 `SOLO_GROUP`. 미션 시작 전이면 `MISSION_NOT_STARTED`, 완료된 유저면 `MISSION_ALREADY_FINISHED`.
2. 문제 캐시에서 PK 를 찾고 난이도가 번호와 같은지 본다(R-B5). 다르거나 없으면 `QUESTION_MISMATCH`.
3. Lua `shown`(그룹 Hash 위에서 원자 실행):
   - 번호 ≠ 진행도 + 1 → `QUESTION_OUT_OF_ORDER`.
   - 이 번호에 지금 문제가 없으면: 이 PK 를 「이 번호의 지금 문제」로 고정.
   - 지금 문제가 같은 PK 면: 그대로(재접속 · 재시도 — 멱등).
   - 지금 문제가 다른 PK 면: `QUESTION_MISMATCH`(바꿀 수 있는 길은 패스뿐 — solve-signal).
   - 새로 고정했으면 이벤트(`question.shown`) XADD.
4. 출제 이력 중복은 **거부하지 않는다**(prd D15). 클라이언트는 문제은행에서 이미 화면에 낸 문제를 빼고 고르고(R-B6), 중복 로그인이 막혀 있어(D16) 정상 흐름에서는 이미 낸 문제가 오지 않는다. 그래도 이미 낸 문제가 오면 진행은 그대로 두고, `worker: persister` 가 기록할 때 알아채 **운영 로그 한 줄**을 stdout 에 남긴다 — 문제은행이 잘못 나간 것이므로 내부 운영자만 본다(§4.1).
5. 새로 고정했으면 `worker: persister` 가 `served_questions`(kind `SHOWN`)에 기록한다. PK `(user_id, question_id)` 가 이미 있고 그 행의 (날짜 · 번호)가 다르면 = 이미 낸 문제 → `ON CONFLICT DO NOTHING` 으로 처음 기록을 남기고 운영 로그를 쓴다(§4.1).

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| 미션 시작 1회 · 시작 시각 고정 | Lua `start` 가 진행도 -1 일 때만 바꾼다. 재시도 · 다른 기기의 동시 요청도 한 번만 시작 |
| 지각 판정(D8) | 시작 시각은 Lua 안 Redis `TIME`. 앱 서버 시계 · 클라이언트 시각을 쓰지 않는다 |
| 평생 한 번 제시(R-B7) | 은행에서 출제 이력을 빼고(서버), 클라이언트가 이미 낸 문제를 다시 고르지 않고(R-B6), 계정당 세션 하나로 두 기기 진행을 막는다(D16). 그래도 중복이 오면 거부하지 않고 운영 로그로 남긴다(D15 · §4.1) |
| 번호마다 문제 하나 | 「이 번호의 지금 문제」는 Lua 안에서만 바뀌고, 바뀌는 길은 진입 신호(빈 자리) · 패스(비우기)뿐 |
| 그날 그룹원 | 07:00 에 한 번 확정해 Redis 에 고정 — 07:00 이후 가입자는 그날 명단에 들어가지 않는다(R-G5) |

**출제 이력의 자리**: 출제 이력의 정본은 PostgreSQL `served_questions` 하나다(Redis 캐시 없음 — 사람 결정). 진입 신호는 Lua(그룹 Hash)에서 「지금 문제」를 고정하고, 이력 기록은 이벤트를 받은 `worker: persister` 가 한다. 한 번호에 두 문제가 나가지 않는 것은 3의 「번호마다 하나」 규칙이 Lua 안에서 지킨다.

### 4.1 운영 로그 — 출제 이력 중복 (D15)
- 사용자 흐름에는 영향이 없다(진입 신호 응답은 정상 `200`). 내부 운영자만 확인한다 — 이번 범위에서는 수집 · 경보를 만들지 않고 `worker: persister` 의 **stdout 로그**로만 남긴다.
- 형식: 로그 수준 `WARN`, 한 줄 JSON — `{"event":"question.served_duplicate","date":"<게임 날짜>","loginId":"<로그인 아이디>","questionNo":<번호>,"questionId":"<PK>"}`.
- 운영 환경에서 로그 수집 · 경보에 거는 방법은 문서로만 다룬다(prd §10).

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 명단 확정 시점 | 07:00 `worker: member` + 요청 시 없으면 그 자리에서 생성 | 요청마다 PostgreSQL 에서 계산 | 07:00 몰림에서 DB 조회를 없앤다. 잡이 늦어도 정합성은 같다 |
| 문제 저장 | `api` 메모리 캐시(기동 시 적재) | 요청마다 Redis · DB 조회 | 문제는 거의 바뀌지 않고 크기가 작다. 은행 뽑기가 메모리 안에서 끝난다 |
| 출제 이력 | PostgreSQL `served_questions` 직접 조회 | Redis 캐시 `served:{<uid>}` | 사람 결정 — 정합성에 캐시가 필요 없고 구조가 단순하다. 07:00 몰림(최대 250만 건 PK 조회)은 읽기 replica 와 ORACLE 부하 기준으로 확인한다 |
| 은행 크기 | 난이도마다 20개 무작위(D10) | 미출제 전체 | 응답 크기를 고정한다. 클라이언트 무작위 선정(R-B6)과 패스 교체에 충분 |
| 「낸 문제」 기록 시점 | 진입 신호(D6) | 풀이 · 패스 신호만 | 화면에 나갔지만 못 푼 문제까지 막는다 |
| 1명 그룹 | 명단에 「1명 그룹」 표시, 문제은행 · 진입 신호를 `SOLO_GROUP` 으로 거부 | 그룹 Hash 를 만들고 판정에서 빼기 | 결과가 없어야 하므로(R-G6) 상태 자체를 만들지 않는다 |

## 6. 위험 · 열린 질문
- 문제 고갈: 문제 수 × 날짜가 쌓이면 미출제 문제가 줄어든다. `QUESTIONS_EXHAUSTED` 가 나기 전에 문제를 보충하는 운영은 문서로만 다룬다(prd §10).
- 진입 신호가 유실되면(네트워크) 그 문제는 기록되지 않는다 — 클라이언트는 성공 응답을 받을 때까지 재시도한다(멱등). 풀이 · 패스는 지금 문제가 고정된 뒤에만 받으므로, 진입 신호 없이 풀린 문제는 생기지 않는다.

## 7. 계약 초안과 다른 점 (`docs/api/openapi.yaml` v0.1 — SPEC 에서 반영)
- 진입 신호 `POST /api/v1/games/today/shown` 이 없다(D6).
- 오류 코드 `NOT_PARTICIPANT` · `QUESTIONS_EXHAUSTED` · `QUESTION_MISMATCH` 가 없다.
- 문제은행이 「난이도마다 20개」(D10)라는 설명이 없다.
