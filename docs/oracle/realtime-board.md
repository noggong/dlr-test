# ORACLE — realtime-board

> 판 v1 · 2026-11-11 · 짝 문서: [HLD](../hld/realtime-board.md) · [LLD](../lld/realtime-board.md) · [SPEC](../spec/realtime-board.md) · [ORACLE](../oracle/realtime-board.md) · 근거: [PRD](../prd.md) v10 FR-5 · §5.8 · R-R1 ~ R-R5 · R-F4 · D2 · D13 · D17

**세 줄 요약**
- 기준 30개(O1 ~ O30) + 동시성 · 불변식 8개(I1 ~ I8). SPEC 의 엔드포인트 3개 · 정상 응답과 오류 코드 5개 · 실시간 이벤트 5종 · 규칙 RB1 ~ RB15 를 모두 하나 이상의 기준이 가리킨다.
- 확인 명령은 Gradle 테스트 4묶음(`core` 순수 함수 · `infra:redis` Lua · `app:api` · `app:realtime` SSE) — 모두 이 기능의 일감이 세운다. Redis 가 필요한 테스트는 Testcontainers `redis:8-alpine`.
- 판정 밖: 250만 연결 부하 · 운영 Redis Cluster 의 구독 비용 · 클라이언트(웹 · 앱)의 재연결 구현 — 이 레포에서 재현할 수 없거나 범위 밖이다.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.realtimeboard.*'` | `BoardView.build` · `GroupDaySnapshot` 파서 — 보는 사람별 규칙 · 순위 · 정렬 표 테스트 (**이 기능의 일감이 세운다** — 파일 `BoardViewTest.kt` · `GroupDaySnapshotTest.kt`) | JDK(Gradle 설정 그대로) · 외부 의존 없음 |
| `cd backend && ./gradlew :infra:redis:test --tests 'com.delightroom.mission.infra.redis.realtimeboard.*'` | Lua `board_read` 판정 순서 · 티켓 `SET NX EX` / `GETDEL` · 그룹 채널 구독 · 코덱 (**일감이 세운다** — `BoardReadLuaTest.kt` · `RealtimeTicketRepositoryTest.kt` · `GroupEventSubscriberTest.kt`) | Docker(Testcontainers `redis:8-alpine`) |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.realtimeboard.*'` | 현황판 조회 · 티켓 발급 HTTP 계약 (**일감이 세운다** — `BoardApiTest.kt` · `RealtimeTicketApiTest.kt`) | Docker(Redis · PostgreSQL 18 컨테이너) |
| `cd backend && ./gradlew :app:realtime:test --tests 'com.delightroom.mission.app.realtime.realtimeboard.*'` | SSE 연결 · 이벤트 5종 · 하트비트 · 종료 · 구독 공유 · 지연 (**일감이 세운다** — `EventStreamTest.kt` · `GroupChannelHubTest.kt` · `DeliveryLatencyTest.kt`) | Docker(Redis · PostgreSQL) |
| `cd backend && ./gradlew :app:api:test :app:realtime:test --tests '*RouteSmokeTest'` | 라우트 = `docs/api/openapi.yaml` (이미 있음 — openapi 갱신 뒤 통과해야 한다) | JDK |
| `python3 tools/build_docs.py --check` | 네 문서가 다 있다 | 레포 루트 · pandoc |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

테스트 규약
- 시각: 판정은 Lua 의 `TIME + offsetUs` 다. Lua 테스트는 창 인자(ARGV 2 · 3)를 `TIME` 기준 앞뒤로 넣어 경계를 만든다(예: 창 끝 = now → 거부, now + 1s → 통과). 앱 테스트는 `game.clock-offset`(game-session LLD 의 `GameProperties.clockOffset`)으로 지금을 07:30 · 08:00:01 · 08:00:04 같은 시각으로 옮긴다.
- 데이터: 그룹 Hash · 명단 Hash · 채널 메시지는 SPEC 예시의 4명 그룹(`junho` · `minji` · `dohyun` · `hana`)을 픽스처로 `HSET` · `SPUBLISH` 해서 만든다 — 신호 Lua(다른 기능)를 부르지 않는다.
- SSE 는 `WebTestClient` 로 `ServerSentEvent<String>` Flux 를 받고, `id` · `event` · `data` 를 그대로 비교한다.

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | 현황판 조회 정상 | SPEC §3 · RB1 · LLD §4.1 | `BoardApiTest` — 창 안에서 `minji` 로 `GET /api/v1/games/today/board` | `200`, 키 4개(`date` · `groupStatus` · `version` · `entries`), `entries` 4개 각 7필드, 값이 SPEC §3 응답 예시와 같다 |
| O2 | 조회 인증 없음 | SPEC §3 오류 | 쿠키 없이 · 만료 토큰으로 | 둘 다 `401`, `code = UNAUTHORIZED` |
| O3 | 조회 시간 창 | RB1 · LLD §3 1 | `BoardReadLuaTest` 모드 `board`: 창 끝 = now(→ 거부) · now + 1s(→ 통과) · 창 시작 = now + 1s(→ 거부) | 거부 2건 `GAME_NOT_OPEN`, 통과 1건 `OK`. API 에서 창 밖 1건 `409 GAME_NOT_OPEN` |
| O4 | 조회 그룹원 아님 | RB3 · LLD §3 3 | 명단 Hash(`#size = 4`)에 없는 uid 로 조회 | `403 NOT_PARTICIPANT` |
| O5 | 조회 1명 그룹 | RB3 · LLD §3 4 | 명단 `#size = 1` · 그 유저 1명 | `409 SOLO_GROUP` |
| O6 | 명단 확정 전 · 그룹 상태 없음 | RB3 · LLD §3 2 · 6 | 명단 Hash 없음 / 명단은 있고 그룹 Hash 없음 | 둘 다 `409 GAME_NOT_OPEN` |
| O7 | 판정 순서 | RB3 | 창 밖 + 그룹원 아님 + 1명 그룹을 겹친 경우 · 그룹원 아님 + 1명 그룹을 겹친 경우 | 각각 `GAME_NOT_OPEN` · `NOT_PARTICIPANT` (먼저 걸린 것) |
| O8 | 티켓 발급 정상 | SPEC §4 · RB4 · LLD §4.2 | `RealtimeTicketApiTest` — 창 안 `POST /api/v1/realtime/tickets` 후 Redis `TTL tk:{<ticket>}` · `GET` | `201`, `ticket` 이 `^[A-Za-z0-9_-]{43}$`, `expiresAt − 응답 시각` 29 ~ 31초, TTL 29 ~ 30, 값의 `uid` · `gid` · `date` 가 요청 유저와 같다 |
| O9 | 티켓 인증 없음 | SPEC §4 오류 | 쿠키 없이 | `401 UNAUTHORIZED`, Redis 에 `tk:*` 키 0개 |
| O10 | 티켓 시간 창(유예 포함) | RB2 | Lua 모드 `ticket`: 창 끝 = now + 1s(08:00:02 에 해당 → 통과) · 창 끝 = now(08:00:03 → 거부) · 창 시작 = now + 1s(07:00 전 → 거부) | 통과 1 · 거부 2(`GAME_NOT_OPEN`). API 창 밖 1건 `409 GAME_NOT_OPEN` · `tk:*` 0개 |
| O11 | 티켓 그룹원 아님 · 1명 그룹 | RB3 | O4 · O5 와 같은 픽스처로 티켓 발급 | `403 NOT_PARTICIPANT` · `409 SOLO_GROUP`, `tk:*` 0개 |
| O12 | SSE 연결 · 첫 메시지 | SPEC §5 · §6.1 · RB5 · LLD §4.3 | `EventStreamTest` — 티켓 발급 → 연결 → 첫 프레임 | `200`, `Content-Type` 이 `text/event-stream` 으로 시작, 첫 프레임 `event = board.snapshot`, `id` = `data.version` = Hash `version`, data 키 5개 |
| O13 | SSE 티켓 오류 | SPEC §5 오류 · RB4 | ① `ticket` 없음 ② `abc`(모양 틀림) ③ 없는 43자 ④ 31초 지난 티켓(`PEXPIRE` 로 만료) ⑤ 한 번 쓴 티켓 | 5건 모두 `401`, `application/json`, `code = REALTIME_TICKET_INVALID` |
| O14 | SSE 그룹 상태 없음 | SPEC §5 오류 | 티켓 발급 뒤 그룹 Hash `DEL` → 연결 | `409 GAME_NOT_OPEN`, 그 노드 구독 수 0 |
| O15 | `member.progressed` | SPEC §6.2 · LLD §4.4 | 연결 뒤 `type = mission.started` · `uid = dohyun` · `version = V0+1` 메시지(LLD §2.3)를 `SPUBLISH` | 두 번째 프레임 `event = member.progressed`, `id = V0+1`, `userId = dohyun`, data 키 6개, `entries` 4개 |
| O16 | `group.pending` | SPEC §6.3 | `type = group.pending` · Hash `pending = 1` | `event = group.pending`, data 키 4개, `groupStatus = PENDING`, `entries` 키 없음 |
| O17 | `group.result` (확정) | SPEC §6.4 · LLD §4.4 | `type = group.decided` · `result = FAILED_IMMEDIATE` · `rankingFinal = 1` | `event = group.result`, `groupStatus = FAILED`, `rankingFinal = true`, `entries` 순위 = Hash `ranking`, 그 뒤 스트림 정상 종료(RB8) |
| O18 | `group.result` 다시 보냄 (유예 풀이) | SPEC §6.4 (2) · R-D5 | `result = FAILED_DEADLINE` · `rankingFinal` 없음인 Hash 로 `type = group.decided` 다음 `type = solve.applied`(version +1, `minji` 진행도 +1) | 프레임 2개 모두 `group.result`, 둘째 `rankingFinal = false` · `minji.progress` +1, 스트림은 열려 있다 |
| O19 | `group.ranking_final` · 종료 | SPEC §6.5 · RB8 | O18 뒤 `type = group.ranking_final` · `rankingFinal = 1` | `event = group.ranking_final`, `rankingFinal = true`, 이 프레임 뒤 1초 안에 스트림 완료 신호 |
| O20 | 보는 사람별 부정 실패 표시 | RB10 · HLD §3.1 · D2 | `BoardViewTest` 표: `hana = CHEAT_FAILED`(진행도 1), 결과 없음 / 있음 × 보는 사람 `hana` / `minji` | 결과 없음: `minji` 가 보면 `hana.status = IN_PROGRESS` · `progress = 1` · `rank = 4`(맨 아래), `hana` 가 보면 `CHEAT_FAILED` · `rank = 4`. 결과 있음: 둘 다 `CHEAT_FAILED`. 다른 필드는 보는 사람 무관하게 같다 |
| O21 | 미접속 · 진행도 표기 | RB10 · R-R1 · D13 | `BoardViewTest` — `progress = -1` | `progress = 0` · `started = false` · `status = NOT_STARTED` · `lastSolvedAt = null` |
| O22 | 순위 · 정렬 · 그룹 상태 | RB11 · LLD §4.5 | `BoardViewTest` — 동률 2명(같은 진행도 · 같은 도착 시각) · 결과 있음(Hash `ranking` 이 계산값과 다르게 들어 있는 픽스처) · `pending = 1` | 동률은 같은 `rank` · 다음 번호 건너뜀 · `userId` 사전순. 결과 있음이면 `rank` = Hash `ranking` 값. `pending = 1` → `PENDING`, `FAILED_DEADLINE` → `FAILED` |
| O23 | 조회 = 스트림 | RB12 | 같은 Hash 에서 `GET …/board` 와 SSE snapshot 을 같은 유저로 | 두 `entries` JSON 이 바이트 단위로 같고 `version` 이 같다 |
| O24 | 옛 메시지 버림 · `id:` | RB5 · RB6 · RB15 | 연결(snapshot `V0 = 10`) 뒤 version 9 · 10 · 12 · 11 · 13 순서로 `SPUBLISH` | 받은 프레임 `id` 가 정확히 `[10, 12, 13]`, 각 `id` = `data.version` |
| O25 | 전달 지연 | HLD §1 목표 · R-R2 | `DeliveryLatencyTest` — 한 그룹 10연결, `SPUBLISH` 1,000회(10ms 간격), 발행 시각 → 각 연결 수신 시각 | 10,000건 모두 수신(유실 0), p99 ≤ 200ms (로컬 컨테이너) |
| O26 | 하트비트 | RB9 · SPEC §6.6 | `realtime.heartbeat = 1s` 로 덮고 3.5초 대기 | 주석 프레임 `ping` 3개(±1), `id` · `event` 없음 |
| O27 | 연결 때 이미 랭킹 확정 | RB8 | Hash `result` · `rankingFinal = 1` 상태에서 연결 | 프레임 1개(`board.snapshot`, `groupStatus` 결과 값) 뒤 스트림 완료 |
| O28 | `Last-Event-ID` 무시 | RB14 | `Last-Event-ID: 3` 헤더로 연결(Hash version 10) | 첫 프레임이 `board.snapshot` `id = 10`, 그 앞에 다른 프레임 없음 |
| O29 | 구독 전에 읽지 않음 | RB5 · LLD §4.3 3 → 4 | `GroupEventSubscriber` 를 감싼 테스트 대역으로 「구독 완료」와 `board_read` 호출 순서를 기록 | 모든 연결에서 구독 완료가 `board_read` 보다 먼저 |
| O30 | 계약 파일 반영 | LLD §1 · HLD §7 | `RouteSmokeTest` 2개 · `docs/api/asyncapi.yaml` 의 메시지 이름 | 종료 0, asyncapi 메시지 이름이 정확히 5개(`board.snapshot` · `member.progressed` · `group.pending` · `group.result` · `group.ranking_final`), MemberEntry 에 `cheatFailed` 없음 |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 티켓은 한 번만 쓰인다 | `RealtimeTicketRepositoryTest` — 티켓 1장에 `take` 100개 동시 | 값을 받은 호출 정확히 1, 나머지 99 `null` |
| I2 | 같은 연결의 `version` 은 늘기만 한다 | O24 · O25 의 수신 기록 전부 | 연결마다 `id` 수열이 엄격히 증가 |
| I3 | 노드 안 그룹 구독은 하나 | `GroupChannelHubTest` — 같은 그룹에 200연결 동시 attach | Redis `PUBSUB SHARDNUMSUB ch:{<b>}:<gid>` = 1 |
| I4 | 연결이 0 이 되면 구독을 푼다 | I3 의 200연결을 동시에 끊고, 그중 50개를 끊는 동안 새 연결 50개 attach(경합) | 모두 끊긴 뒤 `SHARDNUMSUB` = 0 · 레지스트리 연결 수 0. 경합 중 새 연결 50개가 모두 다음 메시지를 받는다 |
| I5 | 결과 확정 전 남의 `CHEAT_FAILED` 는 보이지 않는다 | O20 · O15 · O25 의 모든 수신 프레임 검사 | 결과 없는 그룹 프레임에서 `status = CHEAT_FAILED` 인 줄은 보는 사람 본인 줄뿐(0 건 위반) |
| I6 | `entries` 는 그날 그룹원 전원 · 한 번씩 | 모든 테스트의 `entries` | 길이 = 명단 크기, `userId` 중복 0 |
| I7 | `rank` 는 경쟁 순위 | 모든 테스트의 `entries` | 첫 줄 `rank = 1`, 각 줄 `rank` = (자기보다 앞 순위인 줄 수) + 1 |
| I8 | 구독 끊김에 연결이 남지 않는다 | `EventStreamTest` — 연결 3개 뒤 Redis 구독 연결을 `CLIENT KILL` | 5초 안에 3개 스트림 모두 종료(LLD §5), 이후 새 티켓 연결은 snapshot 을 받는다 |

## 3. 판정 밖
- **250만 동시 연결 · 07:00 연결 몰림**: 노드 50대 · L7 LB 가 필요하다(HLD platform §7 · §9). 로컬 compose 로 재현할 수 없어 O25(한 그룹 10연결 지연)로 대신한다.
- **운영 Redis Cluster 의 sharded Pub/Sub 구독 비용**(HLD §6): 로컬은 단일 Redis 다. 구독 공유(I3 · I4)만 판정한다.
- **클라이언트 재연결 구현**(RB7 · RB13): 서버 계약으로 정했지만 클라이언트(웹 · 앱)는 이 레포의 판정 대상이 아니다(prd §10 · web-client 는 목 데이터 단계). 서버 쪽 전제(1회용 티켓 · snapshot · 종료)만 O13 · O19 · O27 로 판정한다.
- **신호 Lua 가 채널 메시지를 제대로 내는지**: 발행은 game-session · solve-signal · deadline-result 의 ORACLE 이 판정한다. 이 문서는 LLD §2.3 모양의 메시지를 받았을 때만 본다.
- **랭킹 함수 자체의 정확성**: deadline-result ORACLE 이 판정한다. 여기서는 그 함수의 결과를 그대로 싣는지(O22)만 본다.
