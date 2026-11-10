# HLD — realtime-board (현황판 조회 · SSE 실시간 전달)

> 판 v1 · 2026-11-11 · 짝 문서: LLD · SPEC · ORACLE (아직 없음) · 근거: [PRD](../prd.md) v5 FR-5 · §5.8 · D2 · D5 · D13 · 바탕: [HLD platform](platform.md)

**세 줄 요약**
- 현황판은 `api` 의 조회(`GET /games/today/board`)와 `realtime` 의 **SSE** 스트림 두 길로 준다. 둘 다 Redis 그룹 Hash 하나에서 같은 함수로 만든다 — 조회와 스트림이 서로 다른 값을 보이지 않는다.
- 신호 처리 Lua 가 그룹 채널(`ch:{<b>}:<gid>`)에 「바뀐 뒤의 그룹 Hash」를 publish 하면, 그 그룹 그룹원이 붙은 `realtime` 노드가 받아 **보는 사람마다** 현황판을 만들어 보낸다(부정 실패 표시가 본인 · 남에게 다르다 — D2).
- 연결은 `api` 가 주는 30초짜리 **1회용 티켓**으로 연다. 끊기면 클라이언트가 새 티켓으로 다시 열고, 서버는 첫 메시지로 현재 현황판 전체(`board.snapshot`)를 준다 — 놓친 이벤트를 다시 보낼 필요가 없다.

## 1. 배경 · 목표
- 맡는 요구: FR-5 · R-R1 ~ R-R5 · R-F4 · R-F5 · S3 · S4 · S5 · S9 · S10 · S15 · S16 · D2(부정 실패 표시) · D5(보조 필드) · D13(미접속).
- 목표
  - 그룹원이 문제를 풀면 같은 시점에 다른 그룹원 화면이 바뀐다(R-R2) — 목표 지연은 ORACLE 에서 정한다.
  - 그룹 결과가 확정되면 그 즉시 전원에게 결과 메시지와 그룹 내 랭킹이 간다(R-R3).
  - 동시 SSE 연결 최대 250만(HLD platform §7)을 노드 수평 확장으로 받는다. 노드는 무상태.
- 목표 아님: 랭킹 규칙(deadline-result §4) · 신호 판정(solve-signal) · 결과 조회 API(deadline-result).

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `api` BoardController | `GET /api/v1/games/today/board` — 지금 현황판 | BoardService |
| `api` RealtimeTicketController | `POST /api/v1/realtime/tickets` — 로그인 쿠키를 확인하고 1회용 티켓 발급 | RealtimeTicketRepository |
| `realtime` GroupEventStreamController | `GET /realtime/v1/games/today/events?ticket=` — 티켓 소비 · SSE 연결 유지 | RealtimeService |
| RealtimeService (`core`) | 연결 등록 · 그룹 채널 구독 관리 · 보는 사람별 현황판 만들기 · 하트비트 | GroupEventSubscriber · ProgressRepository |
| 현황판 만들기 (`core` BoardService · 공용) | 그룹 Hash + 보는 사람 → 현황판(순위 · 상태 · 보조 필드) | deadline-result §4 랭킹 함수 |

## 3. 흐름
**연결**
1. 클라이언트 → `api` `POST /realtime/tickets`(로그인 쿠키). 티켓 = 무작위 32바이트, Redis `tk:{<ticket>}` 에 (유저 · 그룹 · 날짜)를 30초 TTL 로 저장.
2. 클라이언트 → `realtime` `GET …/events?ticket=`. `GETDEL` 로 티켓을 한 번만 꺼낸다 — 없으면 `401 REALTIME_TICKET_INVALID`.
3. 그 노드가 그 그룹 채널을 아직 구독하지 않았으면 `SSUBSCRIBE ch:{<b>}:<gid>`. 노드 안에서는 그룹마다 구독 하나를 연결 여러 개가 나눠 쓴다.
4. 첫 메시지 `board.snapshot` = Redis 그룹 Hash 로 만든 이 유저 기준 현황판.
5. 15초마다 주석 줄(`: ping`). 클라이언트가 끊기면 연결을 지우고, 그 그룹 연결이 0개가 되면 구독을 푼다.

**변경 전달**
1. 신호 Lua(game-session · solve-signal · deadline-result)가 반영과 함께 `SPUBLISH ch:{<b>}:<gid>` — 내용은 바뀐 뒤의 그룹 Hash 전체(그룹원 ≤ 10명이라 작다)와 Stream 이벤트 id · 바뀐 유저.
2. `realtime` 노드는 그 그룹에 붙은 연결마다 보는 사람 기준 현황판을 만들어 보낸다: 진행 변화는 `member.progressed`(현황판 전체 + 바뀐 유저), 결과 확정은 `group.result`(그룹 상태 + 랭킹).
3. `id:` = Stream 이벤트 id. 클라이언트는 지금까지 받은 id 보다 작거나 같은 메시지를 버린다(한 그룹은 한 버킷 Stream 이라 id 가 커지는 순서가 곧 반영 순서).

**재연결**: 티켓은 1회용이라 `EventSource` 자동 재연결은 401 이 된다. 클라이언트는 `error` 에서 연결을 닫고 1부터 다시 한다. 서버는 놓친 이벤트를 다시 보내지 않고 4의 `board.snapshot` 으로 지금 상태를 준다.

### 3.1 보는 사람별 현황판 규칙
| 그룹원 상태 | 다른 그룹원에게 (결과 확정 전) | 본인에게 | 결과 확정 뒤 모두에게 |
|---|---|---|---|
| 진행도 -1 · 시작 전 | `NOT_STARTED`(「미접속」 · D13) · `started=false` | (본인은 현황판에 오면 이미 시작) | `FAILED`(D4) |
| 진행 중 | `IN_PROGRESS` | `IN_PROGRESS` | `FAILED`(D4) |
| 미션 성공 | `SUCCEEDED` | `SUCCEEDED` | `SUCCEEDED` |
| 부정 실패 | `IN_PROGRESS` · 실패 시점 진행도 · 순위 맨 아래 · `cheatFailed=false` (D2) | `FAILED` · `cheatFailed=true` | `FAILED` · `cheatFailed=true` |
- 진행도는 0~3 으로 보낸다(-1 → 0, R-R1). 순위는 랭킹 규칙 그대로 — 부정 실패는 확정 전에도 맨 아래(D2).
- 보조 필드(D5): `lastSolvedAt` · `passedAtQuestionNo` 는 모두에게 같다.

**조회** `GET /games/today/board`: 07:00 ~ 다음 날 07:00 동안 그날 현황판을 준다(결과 확정 뒤에는 확정 표시). 07:00 전 → `GAME_NOT_OPEN`, 그날 그룹원이 아니면 → `NOT_PARTICIPANT`, 1명 그룹 → `SOLO_GROUP`.

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| 조회 = 스트림 | 둘 다 같은 그룹 Hash · 같은 만들기 함수(§3.1) |
| 순서 | 메시지마다 현황판 전체를 싣고 `id:` 가 커지는 순서만 받는다 — 늦게 온 옛 메시지가 새 상태를 덮지 않는다 |
| 놓친 메시지 | 재연결 때 `board.snapshot` 으로 덮는다. Pub/Sub 은 전달 보장이 없지만 현황판은 매번 전체라 다음 메시지 · 재연결로 맞춰진다 |
| 결과 메시지 | `group.result` 를 놓쳐도 재연결 snapshot 의 그룹 상태 · 결과 조회(deadline-result)가 같은 확정 값을 준다(NFR-4) |
| 티켓 재사용 | `GETDEL` 1회 · TTL 30초 |

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 전달 방식 | SSE (HLD platform §5) | WebSocket · 폴링 | 서버 → 클라이언트 단방향만 필요 |
| 메시지 내용 | 바뀐 뒤 현황판 전체 | 바뀐 칸만(차분) | 그룹 ≤ 10명이라 전체가 작다. 순위가 함께 바뀌고, 놓친 메시지를 다음 메시지가 메운다 |
| 보는 사람별 값 | `realtime` 노드가 연결마다 만든다 | Lua 가 그룹원마다 따로 publish | publish 수가 그룹원 수만큼 늘지 않는다. 다른 점은 부정 실패 표시 하나뿐(§3.1) |
| 놓친 이벤트 | 다시 보내지 않고 snapshot | `Last-Event-ID` 로 Stream 재생 | 현황판 전체가 곧 상태라 재생이 필요 없다. Stream 재생은 노드에 상태를 만든다 |
| 인증 | 30초 1회용 티켓 | 쿼리에 JWT · 쿠키 | `EventSource` 는 헤더를 못 싣는다. URL 에 긴 수명 토큰을 남기지 않는다 |

## 6. 위험 · 열린 질문
- 07:00 에 250만 연결이 한꺼번에 열린다 — 노드 예약 확장 · 연결 수 기반 확장은 HLD platform §9.
- sharded Pub/Sub 구독 수 = 노드마다 붙은 그룹 수. 125만 그룹 × 노드 분산의 구독 비용은 ORACLE 부하 기준으로 확인한다.

## 7. 계약 초안과 다른 점 (`docs/api/openapi.yaml` · `asyncapi.yaml` v0.1 — SPEC 에서 반영)
- `MemberStatus` 에 `NOT_STARTED`(「미접속」 · D13)가 없다.
- 오류 코드 `NOT_PARTICIPANT` 가 없고, 현황판 조회의 `GAME_NOT_OPEN` · `SOLO_GROUP` 응답이 없다.
- 티켓 TTL 30초 · 메시지 `id:` 로 옛 메시지 버리기 규칙이 asyncapi 설명에 없다.
