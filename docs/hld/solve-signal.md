# HLD — solve-signal (풀이 · 패스 · 부정 실패 신호와 그룹 판정)

> 판 v1 · 2026-11-11 · 짝 문서: LLD · SPEC · ORACLE (아직 없음) · 근거: [PRD](../prd.md) v5 FR-4 · §4 · §5.4 ~ §5.7 · NFR-1 ~ NFR-3 · D1 · D2 · D9 · 바탕: [HLD platform](platform.md)

**세 줄 요약**
- 풀이 · 패스 · 부정 실패 신호는 각각 **Redis Lua 한 번**으로 처리한다 — 시간 창(D1) · 순서 · 멱등 · 진행도 반영 · 그룹 판정 · 이벤트 기록 · publish 가 한 원자 단위다.
- 멱등은 자연키다: 같은 (날짜 · 유저 · 번호)의 풀이 신호는 처음 한 번만 반영하고, 다시 오면 같은 결과를 준다. 풀이 · 패스는 그 번호의 「지금 문제」(진입 신호로 고정, game-session)에만 받는다.
- 그룹 결과(성공 · 즉시 실패)는 그룹 Hash 의 `result` 칸을 **없을 때만 쓰기(HSETNX)** 로 한 번만 기록하고, 그때만 결과 이벤트를 낸다(NFR-2).

## 1. 배경 · 목표
- 맡는 요구: FR-4 · R-S1 ~ R-S6 · R-P1 ~ R-P5 · R-F1 ~ R-F6 · R-D2 ~ R-D4 · NFR-1 ~ NFR-3 · D1(제출 가드) · D9(성공 뒤 실패 신호 무시).
- 목표
  - 08:00:03 이후 도착한 풀이 신호는 어떤 경우에도 반영하지 않는다(NFR-1 · D1).
  - 동시에 들어온 신호가 유실 · 중복되지 않는다(NFR-3). 재시도는 같은 결과를 받는다.
  - 그룹 성공 · 실패는 정확히 한 번 기록되고, 그 순간 그룹원 전원에게 전달된다.
- 목표 아님: 정답 판정(클라이언트) · `worker: deadline`(deadline-result) · 랭킹 계산 규칙(deadline-result §4 — 이 기능은 결과 확정 때 그 함수를 부른다) · 전달(realtime-board).

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `api` SignalController | `POST /api/v1/games/today/solves` · `/passes` · `/failures` | SignalService |
| SignalService (`core`) | 요청 검증(번호 1~3 · PK 모양) · 그날 그룹원 · 그룹 버킷 계산 · Lua 호출 · 결과를 응답으로 | ProgressRepository (포트) |
| Lua `solve` · `pass` · `fail` (`infra:redis`) | 그룹 Hash 위에서 판정 · 반영 · 그룹 판정 · `XADD ev:{<b>}` · `SPUBLISH ch:{<b>}:<gid>` | Redis |
| `worker: persister` | 이벤트를 PostgreSQL 에 멱등 기록(`solve_signals` · `daily_progress` · `served_questions` · `group_results`) | LLD platform §3 |

## 3. 흐름
**풀이 신호** `{questionNo, questionId, successTime, clickedAt}` → Lua `solve` (Redis `TIME` = 도착 시각 `t`). 아래 순서대로 보고 처음 걸리는 것으로 끝낸다.
1. 상태: 시작 전 → `MISSION_NOT_STARTED`.
2. 멱등: `questionNo ≤ 진행도` → 반영 없이 지금 진행도 · 상태로 `200`. 이미 반영된 신호의 재시도는 마감 뒤 · 그룹 결과 확정 뒤에 와도 같은 `200` 을 받는다.
3. 상태: 부정 실패 → `MISSION_ALREADY_FINISHED`.
4. 시간 창(D1): `t < 07:00` → `GAME_NOT_OPEN`. `t < 08:00:00` → 통과. `08:00:00 ≤ t < 08:00:03` → `clickedAt < 08:00:00` 일 때만 통과. `t ≥ 08:00:03` → `SUBMISSION_CLOSED`. 그룹 결과가 이미 확정됐으면 → `SUBMISSION_CLOSED`.
5. 순서: `questionNo > 진행도 + 1` → `QUESTION_OUT_OF_ORDER`.
6. 지금 문제: `questionId` ≠ 그 번호의 지금 문제(진입 신호로 고정) → `QUESTION_MISMATCH`. 지금 문제가 없으면(진입 신호 없음) 역시 `QUESTION_MISMATCH`.
7. 반영: 진행도 + 1, 마지막 풀이 도착 시각 = `t`, 지금 문제 비우기. 진행도 3 이면 상태 `SUCCEEDED`.
8. 그룹 판정(§4.1). 이벤트 XADD(`solve.applied` · 결과가 났으면 `group.decided`) · 그룹 채널 publish.
9. 응답: 진행도 · 내 미션 상태 · 그룹 상태.

**패스 신호** `{questionNo, questionId}` → Lua `pass`. 순서대로 보고 처음 걸리는 것으로 끝낸다.
1. 상태: 시작 전 → `MISSION_NOT_STARTED`.
2. 멱등: 이미 이 번호 · 이 PK 로 패스했으면 → 같은 응답 `200`(마감 뒤에 와도). 다른 패스를 이미 썼으면 → `PASS_ALREADY_USED`(R-P5).
3. 상태: 완료(성공 · 부정 실패) → `MISSION_ALREADY_FINISHED`.
4. 시간 창: `07:00 ≤ t < 08:00:00` 만(패스는 제출이 아니라 D1 유예가 없다). 그룹 결과 확정 뒤 → `SUBMISSION_CLOSED`.
5. `questionNo ≠ 진행도 + 1` → `QUESTION_OUT_OF_ORDER`. `questionId` ≠ 지금 문제 → `QUESTION_MISMATCH`.
6. 반영: 패스 번호 · 넘긴 문제 기록, 지금 문제 비우기(클라이언트는 교체 문제로 진입 신호를 보낸다 — game-session). 이벤트 XADD(`pass.used`) · publish(현황판 「n번에서 패스」).

**부정 실패 신호** → Lua `fail`
1. 상태별: 시작 전 → `MISSION_NOT_STARTED`(D14). 미션 성공 → 바꾸지 않고 `200`(D9). 이미 부정 실패 → `200`(멱등). 그룹 결과 확정 뒤 → 바꾸지 않고 `200`(결과는 고정).
2. 반영: 상태 `FAILED_BY_CHEAT`, 실패 시각 = `t`. 진행도는 그 값 그대로(R-F4).
3. 그룹 판정(§4.1) · 이벤트 XADD(`member.cheat_failed`) · publish. 다른 그룹원에게는 결과 확정 전까지 「진행 중」으로 보인다(D2 — realtime-board 가 보는 사람별로 바꿔 보낸다).

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| NFR-1 · D1 마감 | 도착 시각은 Lua 안 Redis `TIME` 하나. 세 구간 판정(§3 풀이 1)을 Lua 안에서 하므로 앱 서버 시계 차이 · 처리 지연이 끼어들지 않는다 |
| NFR-2 1회 기록 | 그룹 결과는 `HSETNX result` 로만 쓴다. 이미 있으면 판정을 건너뛴다. 결과 이벤트는 쓴 쪽만 낸다 |
| NFR-3 유실 · 중복 없음 | 한 그룹의 신호는 같은 슬롯의 Lua 로 하나씩 실행된다(직렬화). 자연키 멱등(§3 풀이 3). Lua 의 반영 · XADD 가 한 원자 단위라 「반영됐는데 이벤트 없음」이 없다 |
| 순서(R-S4) | 번호 = 진행도 + 1 만 반영. 건너뛴 번호는 거부 |
| 판정 순서 | 멱등(이미 반영된 신호)을 시간 창 · 결과 확정보다 먼저 본다 — 마지막 풀이로 그룹이 성공한 뒤 그 요청의 재시도가 `SUBMISSION_CLOSED` 를 받지 않는다 |
| 출제 문제와 일치 | 풀이 · 패스는 진입 신호로 고정된 「지금 문제」에만 받는다 — 서버가 모르는 문제가 풀리지 않는다 |

### 4.1 그룹 판정 (Lua 공통)
그날 그룹원 모두가 「완료」(`SUCCEEDED` 또는 `FAILED_BY_CHEAT`)가 되는 순간에만 결과를 낸다.
| 조건 | 결과(`group_results.result`) | API 그룹 상태 |
|---|---|---|
| 전원 `SUCCEEDED` | `SUCCEEDED` | `SUCCEEDED` |
| 전원 완료 · `FAILED_BY_CHEAT` 한 명 이상(전원 부정 실패 포함) | `FAILED_IMMEDIATE` | `FAILED` |
| 그 밖(진행 중 · 미접속이 남음) | 없음 — 08:00:03 마감 판정(deadline-result) | `IN_PROGRESS` |
- 결과를 쓰는 순간 랭킹 스냅샷(deadline-result §4 규칙)을 같은 Lua 안에서 계산해 결과 이벤트에 싣는다 — 결과 화면 랭킹 = 확정 순간의 상태(NFR-4).

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 판정 위치 | Redis Lua 한 번 | 앱에서 읽고-판정-쓰기(낙관적 잠금) | 07:00 몰림에서 재시도 폭주가 없다. 원자 단위가 판정 · 기록 · 전달을 함께 묶는다 |
| 멱등 키 | 자연키 (날짜 · 유저 · 번호) | `Idempotency-Key` 헤더 | 번호가 하루 3개로 닫혀 있어 자연키로 충분 · 클라이언트 부담이 없다 |
| 재시도 응답 | 같은 `200` + 지금 상태 | `409 이미 반영` | 네트워크 재시도를 오류로 보이지 않게 한다(FR-4) |
| 패스 시간 창 | 08:00:00 까지(유예 없음) | D1 유예 적용 | D1 은 「08:00 전에 제출한 풀이」를 위한 가드다. 패스는 제출이 아니다 |
| 성공 뒤 실패 신호 | 무시(D9) | 실패로 바꿈 | 완료 상태 불변 · NFR-2 |

## 6. 위험 · 열린 질문
- 도착 시각은 Redis 가 받은 시각이라 `api` 앞단 지연(LB · 큐잉)은 「늦게 도착」으로 센다 — 08:00 직후 몰림에서 D1 유예 3초가 그 지연을 덮는지는 ORACLE 부하 기준으로 확인한다.
- Redis 장애 시 최대 1초 유실(NFR-5 허용 범위, HLD platform §4) — 유실된 풀이는 클라이언트 재시도로 다시 반영된다(멱등).

## 7. 계약 초안과 다른 점 (`docs/api/openapi.yaml` v0.1 — SPEC 에서 반영)
- 오류 코드 `QUESTION_MISMATCH` 가 없다.
- `/passes` · `/failures` 의 멱등 응답(같은 `200`) · 성공 뒤 실패 신호 무시(D9)가 설명에 없다.
