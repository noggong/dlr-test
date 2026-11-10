# LLD — realtime-board

> 판 v2 · 2026-11-11 (v2: 통합 — 채널 메시지 §2.3 을 모든 발행 Lua 가 따르도록 맞춤(solve-signal 의 `members` 없앰) · 이벤트 이름 목록은 HLD platform §4.2 · 시각 주입 설정 이름 `game.clock-offset`) · 짝 문서: [HLD](../hld/realtime-board.md) · [LLD](../lld/realtime-board.md) · [SPEC](../spec/realtime-board.md) · [ORACLE](../oracle/realtime-board.md) · 근거: [PRD](../prd.md) v10 FR-5 · §5.8 · R-R1 ~ R-R5 · R-F4 · R-F5 · D2 · D5 · D13 · D17 · 바탕: [HLD platform](../hld/platform.md) §2.1 · §4.1 · [LLD platform](platform.md) §2.1 · 필드 이름: [LLD game-session](game-session.md) §2.2

**세 줄 요약**
- 바뀌는 모듈: `core`(보는 사람별 현황판 만들기 `BoardView` · 티켓 · 서비스 두 개), `infra:redis`(읽기 Lua `board_read` · 티켓 `SET NX EX 30` / `GETDEL` · 그룹 채널 `SSUBSCRIBE`), `app:api`(현황판 조회 · 티켓 발급), `app:realtime`(SSE 스트림 · 노드 안 그룹 구독 공유 · 하트비트).
- 데이터 모델 변화는 없다. 그룹 Hash `g:{<b>}:<gid>:<date>` · 명단 Hash `…:members` 를 **읽기만** 하고, 자기 키는 티켓 `tk:{<ticket>}` 하나다. PostgreSQL 은 읽지 않는다(로그인 아이디까지 그룹 Hash 에 있다). **그룹 채널 메시지 모양(§2.3)의 정본은 이 문서**다.
- 구현 순서: 도메인 타입 · `BoardView`(순수 함수) → Lua `board_read` · 저장소 → 현황판 조회 → 티켓 → 그룹 채널 구독 → SSE 스트림 → 계약(`docs/api/*.yaml`) 반영.

## 1. 모듈 · 파일
`…` 는 `backend/<모듈>/src/main/kotlin/com/delightroom/mission` 이다.

| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `core/domain` `…/core/domain/board/Board.kt` | 수정 | `MemberStatus` 5개(`NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED`) · `GroupStatus` 4개(`IN_PROGRESS` · `PENDING` · `SUCCEEDED` · `FAILED`, `NO_RESULT` 삭제). `BoardEntry` = MemberEntry 필드(§2.4), `Board` 에 `version`. deadline-result 와 같이 쓰는 타입 |
| `core/domain` `…/core/domain/board/GroupDaySnapshot.kt` | 새로 | 그룹 Hash 를 읽은 값(§2.2) — `version` · `pending` · `result` · `decidedAt` · `rankingFinal` · `ranking`(uid → rank) · 그룹원별 `MemberState`. `HGETALL` 평탄 목록 · 채널 `hash` 객체 → 이 타입 파서 |
| `core/domain` `…/core/domain/board/BoardView.kt` | 새로 | **현황판 만들기 함수 하나**(HLD §3.1): `build(snapshot, viewer: UserId, now): Board`. 조회와 스트림이 같이 쓴다(§4.5) |
| `core/domain` `…/core/domain/event/GroupEvent.kt` | 수정 | `GroupChannelMessage(type, groupId, date, streamId, changedUserId, version, at, snapshot)` 로 바꾼다 — 그룹 채널 메시지 한 개(§2.3) |
| `core/domain` `…/core/domain/auth/Credentials.kt` | 수정 | `RealtimeTicket(value, userId, groupId, date, expiresAt)` |
| `core/domain` `…/core/port/GroupBoardRepository.kt` | 새로 | `read(groupId, date, userId, mode): BoardRead` — Lua `board_read` 결과(거부 코드 또는 Redis 시각 + 스냅샷) |
| `core/domain` `…/core/port/RealtimeTicketRepository.kt` | 수정 | `saveNew(ticket, ttl): Boolean`(SET NX) · `take(value): RealtimeTicket?`(GETDEL) |
| `core/domain` `…/core/port/GroupEventSubscriber.kt` | 수정 | `subscribe(groupId): Flow<GroupChannelMessage>` — 수집을 시작하면 `SSUBSCRIBE` 하고, 구독이 확인된 뒤 `subscribed` 신호(`suspend fun awaitSubscribed()`)를 준다 |
| `core/domain` `…/core/service/BoardService.kt` | 수정 | `board(principal)` — §4.1 |
| `core/domain` `…/core/service/RealtimeService.kt` | 수정 | `issueTicket(principal)` §4.2 · `redeemTicket(value)` · `openStream(ticket)` §4.3. 생성자의 `UserRepository` 는 뺀다 |
| `infra/redis` `src/main/resources/lua/board_read.lua` | 새로 | §3 |
| `infra/redis` `…/infra/redis/RedisGroupBoardRepository.kt` | 새로 | `EVALSHA board_read` · 응답 파싱 |
| `infra/redis` `…/infra/redis/RedisRealtimeTicketRepository.kt` | 수정 | `SET tk:{<ticket>} <json> NX EX 30` · `GETDEL` |
| `infra/redis` `…/infra/redis/RedisGroupEventSubscriber.kt` | 수정 | Lettuce 네이티브 pub/sub 연결(`StatefulRedisPubSubConnection` · 운영은 Cluster 판)로 `SSUBSCRIBE` / `SUNSUBSCRIBE` — Spring 의 listener container 는 sharded 를 지원하지 않는다 |
| `infra/redis` `…/infra/redis/GroupChannelCodec.kt` | 새로 | §2.3 JSON ↔ `GroupChannelMessage`. 테스트가 발행용으로도 쓴다 |
| `infra/redis` `…/infra/redis/RedisInfraAutoConfiguration.kt` | 수정 | 위 빈 등록 · 스크립트 SHA 적재 |
| `support/common` `…/common/error/ErrorCode.kt` | 수정 | `NOT_PARTICIPANT(403)` 더함(api-guide §4 에 있고 코드에 없음 — 먼저 머지하는 기능이 더한다) |
| `app/api` `…/app/api/controller/BoardController.kt` | 수정 | `GET /api/v1/games/today/board` → `BoardService.board(@CurrentUser)` |
| `app/api` `…/app/api/controller/RealtimeTicketController.kt` | 수정 | `POST /api/v1/realtime/tickets` → `201` |
| `app/api` `…/app/api/dto/BoardDtos.kt` | 수정 | `BoardResponse(date, groupStatus, version, entries)` · `MemberEntryResponse` — `cheatFailed` 삭제, 필드 7개 모두 싣는다(`null` 허용 필드는 `null` 로) |
| `app/realtime` `…/app/realtime/sse/GroupEventStreamController.kt` | 수정 | `GET /realtime/v1/games/today/events?ticket=` — §4.3. `ticket` 은 `required = false` 로 받아 없으면 직접 `REALTIME_TICKET_INVALID` |
| `app/realtime` `…/app/realtime/sse/GroupChannelHub.kt` | 새로 | 노드 안에서 그룹마다 구독 하나를 공유(§4.4) — `gid → SharedFlow` · 참조 수 · gid 별 `Mutex` |
| `app/realtime` `…/app/realtime/sse/SseConnectionRegistry.kt` | 수정 | 연결 수(전체 · 그룹별) — 지표 `realtime.connections` |
| `app/realtime` `…/app/realtime/sse/SseFrames.kt` | 새로 | `GroupChannelMessage` + 보는 사람 → SSE 프레임(`id:` · `event:` · `data:`) — §4.4 표 |
| `app/realtime` `…/app/realtime/dto/RealtimeMessages.kt` | 수정 | 이벤트 이름 5개 · data 클래스 5개(SPEC §6) — 모두 `version` 포함, `cheatFailed` 삭제 |
| `app/realtime` `src/main/resources/application.yml` | 수정 | `realtime.heartbeat: 15s` · `realtime.connection-buffer: 16` |
| `backend/gradle/libs.versions.toml` · `infra/redis/build.gradle.kts` · `app/*/build.gradle.kts` | 수정 | 테스트 의존 `org.testcontainers:testcontainers`(`redis:8-alpine`) |
| 테스트 (ORACLE §0 의 4묶음) | 새로 | 패키지 `…realtimeboard` |
| `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml` | 수정 | HLD §7 의 차이를 SPEC 대로 반영 |

- 인증 주체는 auth-signup 의 `AuthPrincipal(userId, loginId, groupId)`(`@CurrentUser`) — access token 클레임에서 오므로 저장소를 읽지 않는다.
- `ProgressEventPublisher` · `RedisProgressEventPublisher` 는 이 기능이 쓰지 않는다 — publish 는 신호 Lua 안의 `SPUBLISH` 다(HLD platform §3).

## 2. 데이터 모델
### 2.1 Redis 키 (HLD platform §4.1 과 같은 이름)
`b = gid mod 1024`, `<date>` = KST 게임 날짜 `YYYY-MM-DD`, `<uid>` = `users.id`. 시각 값은 모두 epoch **마이크로초** 정수 **문자열**(Redis `TIME` + `clockOffset`).

| 키 | 타입 | 이 기능 | 쓰는 쪽 |
|---|---|---|---|
| `g:{<b>}:<gid>:<date>` | Hash — 그룹 Hash | 읽기(Lua `board_read`) | game-session · solve-signal · deadline-result Lua |
| `g:{<b>}:<gid>:<date>:members` | Hash — 명단: `#size` · `<uid> → 로그인 아이디` | 읽기(`HEXISTS` · `HGET #size`) | game-session Lua `roster` |
| `ch:{<b>}:<gid>` | sharded Pub/Sub 채널 | 구독(`SSUBSCRIBE`) | 신호 · 마감 Lua 의 `SPUBLISH` |
| `tk:{<ticket>}` | String(JSON) · TTL 30초 | 쓰기(`SET NX EX 30`) · 꺼내기(`GETDEL`) | 이 기능만 |

### 2.2 그룹 Hash 에서 읽는 필드 (이름의 정본: LLD game-session §2.2)
| 필드 | 값 | 이 기능의 쓰임 |
|---|---|---|
| `version` | 정수 — 07:00 명단 확정 때 0, 그룹이 바뀌어 publish 할 때마다 +1 | `id:` · 모든 data 의 `version` |
| `pending` | `1` 또는 없음 | 그룹 상태 `PENDING` |
| `result` | `SUCCEEDED` · `FAILED_IMMEDIATE` · `FAILED_DEADLINE` 또는 없음 | 그룹 상태(`FAILED_*` → `FAILED`) · 결과 확정 여부 |
| `decidedAt` | µs | `decidedAt` |
| `ranking` | 랭킹 JSON 배열 — 형식의 정본은 LLD deadline-result §2.2. 이 기능은 줄마다 uid(`id`)와 순위(`r`)만 읽는다 | 결과 확정 뒤 `rank`(결과 조회 · PostgreSQL 과 같은 값 — NFR-4) |
| `rankingFinal` | `1` · `0` · 없음 | `rankingFinal = (값 == "1")` |
| `<uid>:lid` | 로그인 아이디 | `userId` |
| `<uid>:st` | MemberStatus 5개 | §4.5 |
| `<uid>:p` | `-1` ~ `3` | `progress`(-1 → 0) · `started = (p ≥ 0)` · 순위 |
| `<uid>:late` | `1` · `0` · 없음 | 확정 전 순위(랭킹 키 2′) |
| `<uid>:ls` | µs 또는 없음 | `lastSolvedAt` · 순위 |
| `<uid>:pn` | `1` ~ `3` 또는 없음 | `passedAtQuestionNo` · 순위 |

그룹원 목록은 Hash 의 `<uid>:lid` 필드로 정한다(명단 Hash 와 같은 집합).

### 2.3 그룹 채널 메시지 (`SPUBLISH ch:{<b>}:<gid>` 본문) — 정본
발행하는 Lua(game-session `start` · solve-signal `solve` · `pass` · `fail` · deadline-result `deadline`)는 한 번 실행에 한 번, 아래 JSON 한 줄을 보낸다. 그룹 Hash 전체를 싣는다(HLD §3 변경 전달 1).

| key | 타입 | 설명 |
|---|---|---|
| `type` | 문자열 | 그 실행이 마지막으로 남긴 일: `mission.started` · `solve.applied` · `pass.used` · `member.cheat_failed` · `group.decided` · `group.pending` · `group.ranking_final`. 결과를 처음 쓴 실행은 다른 일과 함께라도 `group.decided` |
| `streamId` | 문자열 · null | 마지막 `XADD ev:{<b>}` id(로그 상관용 — SSE `id:` 로 쓰지 않는다). Stream 에 남기지 않는 `group.pending` 은 `null` |
| `version` | 정수 | 반영 뒤 `version`(= `hash.version`) |
| `date` | `YYYY-MM-DD` | 게임 날짜 |
| `gid` | 문자열 | 그룹 id |
| `uid` | 문자열 · null | 바뀐 그룹원(그룹 단위 일이면 `null`) |
| `at` | µs 문자열 | 반영한 Redis 시각 — `occurredAt` |
| `hash` | 객체 | 반영 뒤 그룹 Hash 전체(`HGETALL` 을 `{필드: 값}` 객체로) |

- 시각 · id 는 문자열이다 — Redis `cjson` 은 숫자를 14자리로 줄여 16자리 µs 를 잃는다.
- 받는 쪽은 모르는 key 를 무시한다. `version` 이 없으면 `hash.version` 을 쓴다.
- `type` 값과 Stream 이벤트의 대응 · 발행 Lua 목록은 HLD platform §4.2 표에 모아 둔다.

### 2.4 MemberEntry (현황판 한 줄 — 고정 계약)
`userId` · `rank`(≥1, 경쟁 순위) · `progress`(0~3) · `status`(MemberStatus) · `started` · `lastSolvedAt`(date-time · null) · `passedAtQuestionNo`(1 · 2 · 3 · null). µs → `+09:00` ISO-8601(소수 6자리). 정렬은 `rank` 오름차순, 같으면 `userId` 바이트 순.

### 2.5 티켓 값 · PostgreSQL
- `tk:{<ticket>}` 값: `{"uid":"<uid>","gid":"<gid>","date":"YYYY-MM-DD"}`. 티켓 = `SecureRandom` 32바이트 → base64url(패딩 없음) 43자.
- PostgreSQL 쿼리: **없음**. 유저 · 그룹 id 는 access token 클레임(auth-signup), 로그인 아이디 · 상태는 그룹 Hash 에 있다. Redis 가 비어 그룹 Hash 가 없으면 재적재(LLD platform §4)가 먼저이고, 이 기능은 `GAME_NOT_OPEN` 을 준다(§5).

## 3. Lua `board_read`
그룹의 키 둘이 같은 해시태그 `{b}` 라 한 번에 읽는다(Cluster 에서도 한 슬롯). 시각 판정은 이 Lua 안의 `TIME` 하나로 한다.

| 항목 | 값 |
|---|---|
| KEYS | `[1]` `g:{<b>}:<gid>:<date>:members` · `[2]` `g:{<b>}:<gid>:<date>` |
| ARGV | `[1]` uid · `[2]` 창 시작 µs · `[3]` 창 끝 µs(이 값 **미만**까지) · `[4]` `offsetUs`(설정 `game.clock-offset` = `GameProperties.clockOffset` 을 µs 로) · `[5]` 모드 `board` · `ticket` · `stream` |
| 창 | 앱이 날짜와 `GameProperties` 로 계산해 넘긴다. `board`: 07:00:00 ~ 08:00:00 · `ticket`: 07:00:00 ~ 08:00:03 · `stream`: `0` ~ `9223372036854775807`(티켓을 낼 때 이미 봤다) |

판정 순서(처음 걸리는 것으로 끝낸다):
1. `now = TIME(µs) + offsetUs`. `now < ARGV[2]` 또는 `now ≥ ARGV[3]` → `{"ERR","GAME_NOT_OPEN"}`.
2. `HGET KEYS[1] #size` 가 없음(명단 확정 전 — game-session `roster` 가 아직 안 돌았다) → `{"ERR","GAME_NOT_OPEN"}`.
3. `HEXISTS KEYS[1] uid = 0` → `{"ERR","NOT_PARTICIPANT"}`(07:00 이후 가입).
4. `#size < 2` → `{"ERR","SOLO_GROUP"}`.
5. 모드 `ticket` → `{"OK", now}`(그룹 Hash 를 읽지 않는다).
6. `EXISTS KEYS[2] = 0` → `{"ERR","GAME_NOT_OPEN"}`(그룹 Hash 없음 — 재적재 전).
7. `{"OK", now, HGETALL KEYS[2]}` — `now` 는 문자열.

- 2 의 「명단 확정 전」: game-session 은 명단이 없으면 요청 경로에서 만든다(`RosterService.findOrCreate`). 이 기능은 만들지 않고 `GAME_NOT_OPEN` 을 준다 — 클라이언트는 문제은행(game-session)을 먼저 부르므로 정상 흐름에서 명단은 이미 있다.

## 4. 처리 절차
### 4.1 현황판 조회 `GET /api/v1/games/today/board`
1. `@CurrentUser` → `uid` · `gid`(없으면 auth 필터가 `401 UNAUTHORIZED`). 날짜 = (`Clock` + `game.clock-offset`)의 KST 날짜 — 날짜만 고른다.
2. `board_read`(모드 `board`). `ERR` → 그 코드로 응답(§5).
3. `GroupDaySnapshot.parse(HGETALL)` → `BoardView.build(snapshot, viewer = uid, now = Lua now)` → `200 { date, groupStatus, version, entries }`.

### 4.2 티켓 발급 `POST /api/v1/realtime/tickets`
1. 4.1 의 1.
2. `board_read`(모드 `ticket`). `ERR` → 그 코드.
3. 티켓 값 생성 → `SET tk:{<ticket>} <json> NX EX 30`. `NX` 실패(충돌 — 사실상 없음)면 한 번 새로 만들어 다시, 또 실패하면 500.
4. `201 { ticket, expiresAt = Lua now + 30초 }`.

### 4.3 SSE 연결 `GET /realtime/v1/games/today/events?ticket=`
1. `ticket` 이 없거나 `^[A-Za-z0-9_-]{43}$` 가 아니면 → `401 REALTIME_TICKET_INVALID`(Redis 를 부르지 않는다).
2. `GETDEL tk:{<ticket>}` → 없으면 `401 REALTIME_TICKET_INVALID`. 만료는 Redis TTL 이 정한다.
3. `GroupChannelHub.attach(gid)` — 이 노드가 그 그룹을 아직 구독하지 않았으면 `SSUBSCRIBE ch:{<b>}:<gid>` 하고 **구독 확인을 기다린다**. 이 연결은 지금부터 오는 메시지를 연결 버퍼(16칸, 넘치면 가장 오래된 것 버림)에 쌓는다.
4. `board_read`(모드 `stream`). `ERR` → `detach` 하고 그 코드로 JSON 응답(스트림을 열지 않는다).
5. 스트림을 연다(`200` · `text/event-stream` · `Cache-Control: no-cache` · `X-Accel-Buffering: no`). 첫 프레임 `board.snapshot`(이 유저 기준, `version = V0`). 이 연결의 `lastVersion = V0`.
6. 버퍼와 그 뒤 메시지를 차례로: `version ≤ lastVersion` 이면 버린다. 아니면 §4.4 표로 프레임을 만들어 보내고 `lastVersion = version`. 3 과 4 사이에 온 변경도 버퍼에 있으므로 빠지지 않는다.
7. 하트비트: 마지막 프레임 뒤 15초마다 주석 `: ping`.
8. 끝: (a) `rankingFinal = 1` 인 Hash 로 프레임을 보낸 직후(5 의 snapshot 포함) 서버가 스트림을 정상 종료한다. (b) 클라이언트가 끊으면 취소. 둘 다 `detach(gid)` — 그 그룹 참조가 0 이면 `SUNSUBSCRIBE`. 요청 헤더 `Last-Event-ID` 는 읽지 않는다.

### 4.4 그룹 채널 메시지 → SSE 이벤트
| 채널 `type` | `hash.result` | SSE 이벤트 |
|---|---|---|
| `group.ranking_final` | 있음 | `group.ranking_final` |
| `group.pending` | 없음 | `group.pending` |
| `group.decided` | 있음 | `group.result` |
| 그 밖(`mission.started` · `solve.applied` · `pass.used` · `member.cheat_failed`) | 없음 | `member.progressed`(`userId` = `hash[<uid>:lid]`) |
| 그 밖 | 있음(08:00 확정 그룹의 유예 풀이) | `group.result` 다시(`rankingFinal` = Hash 값) |
| JSON 파싱 실패 · `hash` 없음 | — | 버리고 경고 로그 `realtime.bad_message` |

- 한 노드가 그룹 메시지 하나를 받으면 파싱은 한 번(`GroupChannelHub`), `BoardView.build` 는 연결마다 한 번이다(그룹 ≤ 10명).
- `GroupChannelHub`: `ConcurrentHashMap<gid, Entry(sharedFlow, refCount)>`. attach · detach 는 gid 별 `Mutex` 로 직렬화 — 「구독 해제 중 새 연결」 경합에서 구독이 두 개 생기거나 연결이 구독 없이 남지 않는다.

### 4.5 보는 사람별 현황판 만들기 `BoardView.build` (HLD §3.1)
입력: 스냅샷 · 보는 사람 uid · 현재 시각(조회는 Lua `now`, 스트림은 메시지 `at`, snapshot 은 Lua `now`). 출력: `Board(date, groupStatus, version, entries)`.
1. 그룹 상태: `result = SUCCEEDED` → `SUCCEEDED` · `result = FAILED_IMMEDIATE | FAILED_DEADLINE` → `FAILED` · 결과 없고 (`pending = 1` 또는 `now ≥ 08:00:00`) → `PENDING` · 그 밖 → `IN_PROGRESS`.
2. 그룹원마다: `progress = max(p, 0)` · `started = (p ≥ 0)` · `lastSolvedAt = ls` · `passedAtQuestionNo = pn`.
3. 상태: 결과 **없고** `st = CHEAT_FAILED` 이고 그 그룹원 ≠ 보는 사람 → `IN_PROGRESS`. 그 밖은 `st` 그대로.
4. 순위: 결과 있으면 Hash `ranking` 의 순위. 없으면 `core` 랭킹 함수(`Ranking.rank` — LLD deadline-result, 부정 실패는 맨 아래)를 **원래 상태**(3 에서 바꾸기 전)로 계산. 순위는 보는 사람과 상관없다.
5. 정렬: `rank` 오름차순, 같으면 `userId` 바이트 순.

## 5. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| access 쿠키 없음 · 만료 (조회 · 티켓) | auth 필터 | `401 UNAUTHORIZED` |
| 조회: `now < 07:00:00` 또는 `≥ 08:00:00` | Lua 1 | `409 GAME_NOT_OPEN` |
| 티켓: `now < 07:00:00` 또는 `≥ 08:00:03` | Lua 1 | `409 GAME_NOT_OPEN` |
| 명단 확정 전 | Lua 2 | `409 GAME_NOT_OPEN` |
| 07:00 이후 가입 | Lua 3 | `403 NOT_PARTICIPANT` |
| 그날 그룹원 1명 | Lua 4 | `409 SOLO_GROUP` |
| 그룹 Hash 없음(재적재 전) | Lua 6 | `409 GAME_NOT_OPEN` |
| 티켓 쿼리 없음 · 모양 틀림 · 없음 · 30초 지남 · 이미 씀 | §4.3 1 · 2 | `401 REALTIME_TICKET_INVALID`(JSON, 스트림 열지 않음) |
| 같은 티켓으로 동시에 두 연결 | `GETDEL` 원자 — 한쪽만 값을 받는다 | 다른 쪽 `401 REALTIME_TICKET_INVALID` |
| 연결 때 이미 `rankingFinal = 1` | snapshot 한 프레임 뒤 정상 종료 | `200` 스트림 1프레임 |
| 늦게 온 옛 메시지(`version ≤ lastVersion`) | 버림 | 보내지 않음 |
| 느린 클라이언트(버퍼 16칸 넘침) | 가장 오래된 것 버림 — 메시지마다 상태 전체라 다음 메시지가 메운다 | — |
| Redis 구독 연결 끊김 | 그 노드의 해당 그룹 스트림을 모두 닫는다(구독이 끊긴 사이 메시지를 잃었을 수 있다). 클라이언트가 새 티켓으로 다시 붙어 snapshot 을 받는다 | 스트림 종료 |
| 같은 유저가 여러 탭 | 티켓 · 연결을 따로 센다(제한 없음) | — |
| 그룹 Hash 의 그룹원 필드 일부 없음(`ls` · `pn` · `late`) | 없음 = `null` · `false` | — |

## 6. 구현 순서
1. `ErrorCode.NOT_PARTICIPANT` · `Board.kt` 타입 정리(MemberStatus 5 · GroupStatus 4 · `version`) · DTO 정리(`cheatFailed` 삭제) — deadline-result 와 함께 쓰므로 먼저.
2. `GroupDaySnapshot` 파서 · `BoardView.build` + 단위 테스트(ORACLE O20 ~ O22). 랭킹은 deadline-result 의 `Ranking` 을 부르고, 그 전에는 테스트 대역.
3. Lua `board_read` · `RedisGroupBoardRepository` + Testcontainers Redis 테스트(판정 순서 7단계 — ORACLE O3 ~ O7 · O10 · O11).
4. `BoardService.board` · `BoardController` + API 테스트(O1 · O2).
5. 티켓: `RealtimeTicket` · `RedisRealtimeTicketRepository` · `RealtimeService.issueTicket` · `RealtimeTicketController`(O8 · O9 · I1).
6. `GroupChannelCodec` · `RedisGroupEventSubscriber`(SSUBSCRIBE) · `GroupChannelHub`(I3 · I4).
7. `GroupEventStreamController` · `SseFrames` · 하트비트 · 종료 조건 + SSE 통합 테스트(O12 ~ O19 · O23 ~ O29 · I8).
8. `docs/api/openapi.yaml` · `asyncapi.yaml` 를 SPEC 대로 고치고 `RouteSmokeTest` 통과(O30).
9. 지연 측정 테스트(O25) · 연결 수 지표.
