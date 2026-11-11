# LLD — realtime-board

> 판 v7.4 · 2026-11-11 (v7.4: T-RB-7 계약 반영 — `core/domain` testFixtures 에 `AsyncApiContract`(asyncapi 메시지 이름 · 스키마 필드 읽기) · `OpenApiContract.schemaFields` 를 더하고, 두 앱의 `RouteSmokeTest` 가 계약 파일 필드를 DTO 와 대조한다(ORACLE O30) — 메시지 이름은 `RealtimeEvent` 상수 전체 · `RealtimeMessage` sealed 하위 타입 전체 · asyncapi 채널 메시지와도 맞추고, 필드는 리플렉션이 아니라 앱 `JsonMapper` 로 직렬화한 JSON 키로 대조한다 · 두 앱의 `tasks.test` 가 계약 yaml 을 `inputs.file` 로 선언한다(경로를 시스템 속성으로만 넘기면 yaml 만 바뀐 빌드에서 테스트가 `UP-TO-DATE` 로 건너뛰어진다) · 구현을 마친 세 오퍼레이션의 openapi `501` 을 지운다 · v7.3: T-RB-8 — 연결 수 게이지 `realtime.connections`(태그 없음 · 노드 전체 연결 수)를 `SseConnectionMetrics`(Micrometer `MeterBinder`)로 등록한다. `SseConnectionRegistry` 는 지표를 모른다. 새 의존은 없다 — `mission.spring-boot-app` 이 이미 actuator 를 싣고 Boot 가 `MeterRegistry` 를 세운다. 밖으로 내보내기(Prometheus 등 · `/metrics` 노출)는 운영 관측(HLD platform §9)이라 더하지 않는다 · 그룹별 태그는 그룹 수만큼 시계열이 생겨 두지 않는다 · v7.2: T-RB-6 구현 반영 — 구독이 끊겨 연결 버퍼가 `GroupChannelLostException` 으로 닫히면 스트림을 예외가 아니라 **정상 종료**한다(응답이 이미 `200` 이라 예외는 서버 오류 로그만 남긴다 · 경고 로그 `realtime.channel_lost`) · 하트비트는 연결 버퍼를 `select`(받기 · `onTimeout`)로 기다려 만든다 — `withTimeout` 으로 받기를 취소하면 그 사이 온 메시지를 잃을 수 있다 · 하트비트 시계는 마지막으로 **보낸** 프레임 기준이라 버린 메시지는 시계를 되돌리지 않는다 · 주석은 `comment(" ping")` 으로 써야 `: ping` 바이트가 된다 · 현황판 만들기 · 직렬화가 예외를 내도 그 메시지만 버린다(`realtime.bad_message`) · `SseFrames.event(message, viewer)` 가 §4.4 표를 맡고, 표에 맞지 않는 메시지(`group.decided` · `group.ranking_final` 인데 결과 없음 · `group.ranking_final` 인데 `rankingFinal ≠ 1` · 결과 있는데 `decidedAt` 없음 · 바뀐 그룹원이 Hash 에 없음)는 버리고 `realtime.bad_message` · v7.1: 리뷰 반영 — `SseConnectionReleaseFilter`(WebFilter)가 요청이 끝날 때(본문 수집 전 취소 포함) 그 요청이 붙인 연결을 `detach` 한다 · v7: T-RB-5 구현 반영 — core 는 노드 안 구독 공유(`GroupChannelHub` · app:realtime)를 모르므로 `openStream(ticket)` 대신 `RealtimeService.redeemTicket(value)`(모양 · `GETDEL`) · `RealtimeService.snapshot(ticket): StreamSnapshot`(`board_read` 모드 `stream` → `BoardView.build` · `rankingFinal` · Lua 시각)으로 나누고, 「구독 → 읽기」 차례는 `GroupEventStreamController` 가 지킨다 · `RealtimeService` 생성자에서 `GroupEventSubscriber` 를 뺀다 · snapshot 은 티켓의 `date` 로 읽는다 · `SseFrames` 는 `data` 를 앱 `JsonMapper` 로 문자열로 만들어 `ServerSentEvent<String>` 으로 낸다(조회와 같은 바이트 — RB12) · v6: T-RB-3 구현 반영 — `RealtimeService.issueTicket(userId, groupId)`(core 는 `AuthPrincipal` 을 모른다) · 티켓 값 JSON 에 `exp`(만료 µs)를 더한다 — `take` 가 돌려주는 `RealtimeTicket` 의 `expiresAt` 을 채우려고 · 티켓 값 생성 · TTL 30초는 `RealtimeTicket.newValue` · `RealtimeTicket.TTL` · `RedisRealtimeTicketRepository` 는 `RedisKeys` 로 키를 만든다 · v5: T-RB-2 구현 반영 — 포트는 `GroupBoardRepository.read(groupId, date, userId, mode: BoardReadMode): BoardRead`(`Rejected(code)` · `Opened(now, fields)` — 창은 저장소가 `mode` 와 `GameProperties` 로 계산) · `BoardService.board(userId, groupId)`(core 는 app 의 `AuthPrincipal` 을 모른다) · 그룹 Hash 파싱 실패(`IllegalArgumentException`)는 따로 처리하지 않고 `500` — Lua 만 쓰는 Hash 가 틀린 것은 클라이언트가 고칠 수 없는 서버 결함이다 · `lastSolvedAt` 은 소수 6자리 고정 · `board_read` ARGV[6] = 최소 그룹원 수(`game.min-group-size`) — Lua 에 2 를 박지 않는다 · v4: T-RB-4 구현 반영 — `GroupEventSubscriber.subscribe` 가 구독 확인을 콜백 `onSubscribed` 로 알린다 · 채널 `type` 은 enum `GroupChannelType` · `GroupChannelHub` 는 `SharedFlow` 대신 연결마다 16칸 버퍼로 직접 나눠 준다 · gid 별 `Mutex` 는 줄무늬(256개) · 노드의 구독 연결은 하나이고 끊기면 그 노드의 그룹 구독이 모두 예외로 끝난다 · `ProgressEventPublisher` 는 `GroupChannelMessage` 를 받는다 · 구독 · 해제 확인 5초 제한(넘으면 구독 연결을 버린다) · 모르는 `type` 도 버린다 · `detach` 는 취소 중에도 끝까지 한다 · v3: T-RB-1 구현 반영 — 그룹원 상태 enum 은 `MemberStatus` 하나(`MissionStatus` 삭제 · `MissionProgress.status` 도 이것) · `Board.rank` 삭제(순위는 `BoardView`) · `BoardView.build` 에 `deadlineAt` 인자 · `GroupDaySnapshot.parse` 가 날짜를 받는다 · v2: 통합 — 채널 메시지 §2.3 을 모든 발행 Lua 가 따르도록 맞춤(solve-signal 의 `members` 없앰) · 이벤트 이름 목록은 HLD platform §4.2 · 시각 주입 설정 이름 `game.clock-offset`) · 짝 문서: [HLD](../hld/realtime-board.md) · [LLD](../lld/realtime-board.md) · [SPEC](../spec/realtime-board.md) · [ORACLE](../oracle/realtime-board.md) · 근거: [PRD](../prd.md) v10 FR-5 · §5.8 · R-R1 ~ R-R5 · R-F4 · R-F5 · D2 · D5 · D13 · D17 · 바탕: [HLD platform](../hld/platform.md) §2.1 · §4.1 · [LLD platform](platform.md) §2.1 · 필드 이름: [LLD game-session](game-session.md) §2.2

**세 줄 요약**
- 바뀌는 모듈: `core`(보는 사람별 현황판 만들기 `BoardView` · 티켓 · 서비스 두 개), `infra:redis`(읽기 Lua `board_read` · 티켓 `SET NX EX 30` / `GETDEL` · 그룹 채널 `SSUBSCRIBE`), `app:api`(현황판 조회 · 티켓 발급), `app:realtime`(SSE 스트림 · 노드 안 그룹 구독 공유 · 하트비트).
- 데이터 모델 변화는 없다. 그룹 Hash `g:{<b>}:<gid>:<date>` · 명단 Hash `…:members` 를 **읽기만** 하고, 자기 키는 티켓 `tk:{<ticket>}` 하나다. PostgreSQL 은 읽지 않는다(로그인 아이디까지 그룹 Hash 에 있다). **그룹 채널 메시지 모양(§2.3)의 정본은 이 문서**다.
- 구현 순서: 도메인 타입 · `BoardView`(순수 함수) → Lua `board_read` · 저장소 → 현황판 조회 → 티켓 → 그룹 채널 구독 → SSE 스트림 → 계약(`docs/api/*.yaml`) 반영.

## 1. 모듈 · 파일
`…` 는 `backend/<모듈>/src/main/kotlin/com/delightroom/mission` 이다.

| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `core/domain` `…/core/domain/board/Board.kt` | 수정 | `MemberStatus` 5개(`NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED`) — 그룹원 상태의 **유일한** enum(`progress.MissionStatus` 는 지우고 `MissionProgress.status` 도 이 타입). `GroupStatus` 4개(`IN_PROGRESS` · `PENDING` · `SUCCEEDED` · `FAILED`, `NO_RESULT` 삭제). `BoardEntry` = MemberEntry 필드(§2.4), `Board(date, groupStatus, version, entries)`. `Board.rank` 는 지운다 — 순위는 `BoardView` 가 `Ranking.rank` 로 붙인다. deadline-result 와 같이 쓰는 타입 |
| `core/domain` `…/core/domain/board/GroupDaySnapshot.kt` | 새로 | 그룹 Hash 를 읽은 값(§2.2) — `date` · `version` · `pending` · `result` · `decidedAt` · `rankingFinal` · `ranking`(uid → rank) · 그룹원별 `MemberState`. `parse(date, fields: Map)` · `parseFlat(date, HGETALL 평탄 목록)` — 채널 `hash` 객체도 `Map` 으로 넘긴다. 필드가 빠졌거나 값이 틀리면 `IllegalArgumentException` — 진행도 1~3 인데 `ls` 없음 · 그룹원 `lid` 겹침 · `ranking` 의 같은 `id` 두 줄 · `late` · `rankingFinal` 이 `0`/`1`/없음 밖 · `pending` 이 `1`/없음 밖도 거부한다(랭킹 키를 조용히 바꾸지 않도록). `ranking` JSON 은 Jackson 트리로 읽는다(core 에 `jackson-module-kotlin` 의존) |
| `core/domain` `…/core/domain/board/BoardView.kt` | 새로 | **현황판 만들기 함수 하나**(HLD §3.1): `build(snapshot, viewer: UserId, now: Instant, deadlineAt: Instant): Board` — `deadlineAt` = 그날 08:00:00(호출하는 서비스가 `GameProperties` 로 계산; 도메인은 설정을 모른다). 조회와 스트림이 같이 쓴다(§4.5) |
| `core/domain` `…/core/domain/event/GroupEvent.kt` | 수정 | `GroupChannelMessage(type, groupId, date, streamId, changedUserId, version, at, snapshot)` 로 바꾼다 — 그룹 채널 메시지 한 개(§2.3). `type` 은 `GroupChannelType`(§2.3 의 7개 · `wire` = 채널 문자열), `snapshot` 은 `hash` 를 `GroupDaySnapshot.parse` 한 값. `ProgressEventPublisher.publish` 도 이 타입을 받는다(발행은 Lua 라 이 기능은 쓰지 않는다) |
| `core/domain` `…/core/domain/auth/Credentials.kt` | 수정 | `RealtimeTicket(value, userId, groupId, date, expiresAt)` · `RealtimeTicket.TTL`(30초) · `RealtimeTicket.newValue(random)`(§2.5) · `RealtimeTicket.isWellFormed(value)`(`^[A-Za-z0-9_-]{43}$` — §4.3 1 이 쓴다) |
| `core/domain` `…/core/port/GroupBoardRepository.kt` | 새로 | `read(groupId, date, userId, mode: BoardReadMode): BoardRead` — Lua `board_read` 결과. `BoardReadMode` = `BOARD` · `TICKET` · `STREAM`(창은 구현이 §3 표대로 `GameProperties` 로 계산). 두 타입은 `core/domain/board/BoardRead.kt` 에 둔다(`core.port` 는 인터페이스만 — ArchitectureTest). `BoardRead` = `Rejected(code: ErrorCode)`(`GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP`) · `Opened(now: Instant, fields: List<String>)`(`HGETALL` 평탄 목록 — `TICKET` 은 빈 목록) |
| `core/domain` `…/core/port/RealtimeTicketRepository.kt` | 수정 | `saveNew(ticket, ttl): Boolean`(SET NX) · `take(value): RealtimeTicket?`(GETDEL) |
| `core/domain` `…/core/port/GroupEventSubscriber.kt` | 수정 | `subscribe(groupId, onSubscribed: () -> Unit): Flow<GroupChannelMessage>` — 수집을 시작하면 `SSUBSCRIBE` 하고, Redis 의 구독 확인을 받은 뒤 `onSubscribed` 를 한 번 부른다. 수집을 취소하면 `SUNSUBSCRIBE` 확인까지 기다린 뒤 끝난다. 구독 연결이 끊기면 `GroupChannelLostException` 으로 끝난다. 한 노드에서 같은 그룹을 동시에 두 번 구독하지 않는다(`GroupChannelHub` 가 보장 · 어기면 `IllegalStateException`) |
| `core/domain` `…/core/service/BoardService.kt` | 수정 | `board(userId, groupId)` — §4.1(컨트롤러가 `@CurrentUser` 에서 꺼내 넘긴다) |
| `core/domain` `…/core/service/RealtimeService.kt` | 수정 | `issueTicket(userId, groupId)` §4.2(컨트롤러가 `@CurrentUser` 에서 꺼내 넘긴다) · `redeemTicket(value): RealtimeTicket`(§4.3 1 · 2) · `snapshot(ticket): StreamSnapshot`(§4.3 4 — `board`(티켓 주인 기준 `Board`) · `rankingFinal` · `at`(Lua 시각)). 생성자는 `(tickets, clock, boards, properties)` — 그룹 채널 구독은 app:realtime 의 `GroupChannelHub` 가 하므로 `GroupEventSubscriber` · `UserRepository` 는 받지 않는다 |
| `infra/redis` `src/main/resources/lua/board_read.lua` | 새로 | §3 |
| `infra/redis` `…/infra/redis/RedisGroupBoardRepository.kt` | 새로 | `EVALSHA board_read` · 응답 파싱 |
| `infra/redis` `…/infra/redis/RedisRealtimeTicketRepository.kt` | 수정 | `SET tk:{<ticket>} <json> NX EX 30` · `GETDEL`(키는 `RedisKeys.ticket`) |
| `infra/redis` `…/infra/redis/RedisGroupEventSubscriber.kt` | 수정 | Lettuce 네이티브 pub/sub 연결(`StatefulRedisPubSubConnection` · 운영은 Cluster 판)로 `SSUBSCRIBE` / `SUNSUBSCRIBE` — Spring 의 listener container 는 sharded 를 지원하지 않는다. 연결은 `LettuceConnectionFactory` 의 네이티브 클라이언트로 **첫 구독 때** 하나 열어 노드의 모든 그룹이 같이 쓴다(api 앱 기동은 연결하지 않는다). 그 연결이 끊기면(`onRedisDisconnected`) 연결을 닫고 살아 있는 구독을 모두 `GroupChannelLostException` 으로 끝낸다 — Lettuce 자동 재구독에 맡기면 끊긴 사이 메시지를 잃은 채 이어진다(§5). 다음 구독은 새 연결을 연다. 파싱 실패 메시지는 버리고 경고 로그 `realtime.bad_message`. 운영 Cluster 판(`RedisClusterClient`)에서 노드 연결 하나의 끊김이 이 연결의 `onRedisDisconnected` 로 오는지는 검증하지 않았다(ORACLE §3 판정 밖) |
| `infra/redis` `…/infra/redis/GroupChannelCodec.kt` | 새로 | §2.3 JSON ↔ `GroupChannelMessage`. 테스트가 발행용으로도 쓴다 |
| `infra/redis` `…/infra/redis/RedisInfraAutoConfiguration.kt` | 수정 | 위 빈 등록 · 스크립트 SHA 적재 |
| `support/common` `…/common/error/ErrorCode.kt` | 수정 | `NOT_PARTICIPANT(403)` 더함(api-guide §4 에 있고 코드에 없음 — 먼저 머지하는 기능이 더한다) |
| `app/api` `…/app/api/controller/BoardController.kt` | 수정 | `GET /api/v1/games/today/board` → `BoardService.board(@CurrentUser)` |
| `app/api` `…/app/api/controller/RealtimeTicketController.kt` | 수정 | `POST /api/v1/realtime/tickets` → `201` |
| `app/api` `…/app/api/dto/BoardDtos.kt` | 수정 | `BoardResponse(date, groupStatus, version, entries)` · `MemberEntryResponse` — `cheatFailed` 삭제, 필드 7개 모두 싣는다(`null` 허용 필드는 `null` 로) |
| `app/realtime` `…/app/realtime/sse/GroupEventStreamController.kt` | 수정 | `GET /realtime/v1/games/today/events?ticket=` — §4.3. `ticket` 은 `required = false` 로 받아 없으면 직접 `REALTIME_TICKET_INVALID`. `redeemTicket` → `GroupChannelHub.attach` → `snapshot` 차례(구독이 읽기보다 먼저 — RB5)를 여기서 지키고, `ResponseEntity<Flow<ServerSentEvent<String>>>` 로 헤더(`Cache-Control: no-cache` · `X-Accel-Buffering: no`)를 싣는다. 스트림이 어떻게 끝나든 `finally` 에서 `detach` |
| `app/realtime` `…/app/realtime/sse/GroupChannelHub.kt` | 새로 | 노드 안에서 그룹마다 구독 하나를 공유(§4.4) — `attach(gid): GroupChannelConnection`(구독 확인 뒤 돌아온다) · `detach(connection)`. `gid → Entry(구독 Job, 연결 목록)` · 참조 수 = 연결 목록 크기 · gid 별 `Mutex`(줄무늬 256개) |
| `app/realtime` `…/app/realtime/sse/SseConnectionRegistry.kt` | 수정 | 연결 수(전체 · 그룹별) — 지표는 모른다 |
| `app/realtime` `…/app/realtime/sse/SseConnectionMetrics.kt` | 새로 | `MeterBinder` — 게이지 `realtime.connections`(태그 없음 · 단위 `connections`) = `SseConnectionRegistry.connectionCount()`. 읽을 때마다 레지스트리를 보므로 따로 올리고 내리지 않는다(ORACLE O31) |
| `app/realtime` `…/app/realtime/sse/SseConnectionReleaseFilter.kt` | 새로 | WebFilter — 컨트롤러가 attach 한 연결을 요청 속성에 남기면, 요청이 어떻게 끝나든(`doFinally` — 본문을 다 씀 · 끊김 · 본문을 수집하기 전 취소) `detach` 한다. 스트림 `finally` 는 본문을 수집해야 돌기 때문이다(`detach` 는 두 번 불러도 한 번) |
| `app/realtime` `…/app/realtime/sse/SseFrames.kt` | 새로 | `snapshot(StreamSnapshot)` · `event(GroupChannelMessage, viewer): StreamFrame?`(§4.4 표 — 프레임 · `last`(= `rankingFinal`), 표 밖이면 null) · `heartbeat()`(주석 `ping`) → SSE 프레임(`id:` · `event:` · `data:`) — §4.3 5 · §4.4 표. `data` 는 앱 `JsonMapper`(현황판 조회 응답과 같은 설정)로 직렬화한 문자열이다 — 프레임 쓰기가 문자열을 다시 인코딩하지 않으므로 조회와 snapshot 의 `entries` 가 바이트까지 같다(RB12). 시각 필드는 `@JsonFormat(MICROS_ISO_PATTERN)`(소수 6자리) |
| `app/realtime` `…/app/realtime/dto/RealtimeMessages.kt` | 수정 | 이벤트 이름 5개 · data 클래스 5개(SPEC §6) — 모두 `version` 포함, `cheatFailed` 삭제 |
| `core/domain` `src/testFixtures/…/core/testfixture/AsyncApiContract.kt` | 새로 | `docs/api/asyncapi.yaml`(시스템 속성 `asyncapi.path`) — `messageNames()`(components.messages 의 `name`) · `payloadFields(messageName)` · `schemaFields(schema)`(`properties` 와 `required` 가 다르면 실패). `OpenApiContract.schemaFields(schema)` 도 같은 모양으로 더한다 |
| `app/realtime` `src/test/…/app/realtime/RouteSmokeTest.kt` | 수정 | O30 — asyncapi 메시지 이름(components = 채널 = 오퍼레이션) = `RealtimeEvent` 상수 5개 = sealed 하위 타입 5개 · 메시지별 `payload` 필드 = 앱 `JsonMapper` 직렬화 키(null 필드 포함) · MemberEntry 7필드 · 두 계약 파일에 `cheatFailed` 없음 |
| `app/api` `src/test/…/app/api/RouteSmokeTest.kt` | 수정 | O30 — openapi `MemberEntry` · `BoardResponse` · `RealtimeTicketResponse` 필드 = 앱 `JsonMapper` 로 직렬화한 DTO 키(null 필드 포함) |
| `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml` | 수정 | SPEC §3 ~ §6 그대로. 구현한 세 오퍼레이션의 `501` 삭제, 구독 끊김의 정상 종료(SPEC §5 · RB8) · 하트비트 `: ping` 은 메시지가 아니라 주석 줄 |
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
- `tk:{<ticket>}` 값: `{"uid":"<uid>","gid":"<gid>","date":"YYYY-MM-DD","exp":"<만료 µs>"}` — 모두 문자열. `exp` = 발급 Lua 시각 + 30초(응답 `expiresAt` 과 같은 값)로, `take` 가 돌려주는 `RealtimeTicket.expiresAt` 을 채운다. 만료 판정은 `exp` 가 아니라 Redis TTL 이 한다. 티켓 = `SecureRandom` 32바이트 → base64url(패딩 없음) 43자.
- PostgreSQL 쿼리: **없음**. 유저 · 그룹 id 는 access token 클레임(auth-signup), 로그인 아이디 · 상태는 그룹 Hash 에 있다. Redis 가 비어 그룹 Hash 가 없으면 재적재(LLD platform §4)가 먼저이고, 이 기능은 `GAME_NOT_OPEN` 을 준다(§5).

## 3. Lua `board_read`
그룹의 키 둘이 같은 해시태그 `{b}` 라 한 번에 읽는다(Cluster 에서도 한 슬롯). 시각 판정은 이 Lua 안의 `TIME` 하나로 한다.

| 항목 | 값 |
|---|---|
| KEYS | `[1]` `g:{<b>}:<gid>:<date>:members` · `[2]` `g:{<b>}:<gid>:<date>` |
| ARGV | `[1]` uid · `[2]` 창 시작 µs · `[3]` 창 끝 µs(이 값 **미만**까지) · `[4]` `offsetUs`(설정 `game.clock-offset` = `GameProperties.clockOffset` 을 µs 로) · `[5]` 모드 `board` · `ticket` · `stream` · `[6]` 최소 그룹원 수(`GameProperties.minGroupSize` — 기본 2) |
| 창 | 앱이 날짜와 `GameProperties` 로 계산해 넘긴다. `board`: 07:00:00 ~ 08:00:00 · `ticket`: 07:00:00 ~ 08:00:03 · `stream`: `0` ~ `9223372036854775807`(티켓을 낼 때 이미 봤다) |

판정 순서(처음 걸리는 것으로 끝낸다):
1. `now = TIME(µs) + offsetUs`. `now < ARGV[2]` 또는 `now ≥ ARGV[3]` → `{"ERR","GAME_NOT_OPEN"}`.
2. `HGET KEYS[1] #size` 가 없음(명단 확정 전 — game-session `roster` 가 아직 안 돌았다) → `{"ERR","GAME_NOT_OPEN"}`.
3. `HEXISTS KEYS[1] uid = 0` → `{"ERR","NOT_PARTICIPANT"}`(07:00 이후 가입).
4. `#size < ARGV[6]`(기본 2) → `{"ERR","SOLO_GROUP"}`.
5. 모드 `ticket` → `{"OK", now}`(그룹 Hash 를 읽지 않는다).
6. `EXISTS KEYS[2] = 0` → `{"ERR","GAME_NOT_OPEN"}`(그룹 Hash 없음 — 재적재 전).
7. `{"OK", now, HGETALL KEYS[2]}` — `now` 는 문자열.

- 2 의 「명단 확정 전」: game-session 은 명단이 없으면 요청 경로에서 만든다(`RosterService.findOrCreate`). 이 기능은 만들지 않고 `GAME_NOT_OPEN` 을 준다 — 클라이언트는 문제은행(game-session)을 먼저 부르므로 정상 흐름에서 명단은 이미 있다.

## 4. 처리 절차
### 4.1 현황판 조회 `GET /api/v1/games/today/board`
1. `@CurrentUser` → `uid` · `gid`(없으면 auth 필터가 `401 UNAUTHORIZED`). 날짜 = (`Clock` + `game.clock-offset`)의 KST 날짜 — 날짜만 고른다.
2. `board_read`(모드 `board`). `ERR` → 그 코드로 응답(§5).
3. `GroupDaySnapshot.parseFlat(date, HGETALL)` → `BoardView.build(snapshot, viewer = uid, now = Lua now, deadlineAt = 그날 08:00:00)` → `200 { date, groupStatus, version, entries }`. 시각은 `+09:00` · 소수 6자리(`yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX`).
4. 파서가 `IllegalArgumentException`(그룹 Hash 가 틀림)을 던지면 잡지 않는다 → `500`. 그룹 Hash 는 Lua 만 쓰므로 틀렸다면 서버 결함이고, 클라이언트가 다시 해서 나을 일이 아니다 — 오류 코드를 따로 만들지 않는다.

### 4.2 티켓 발급 `POST /api/v1/realtime/tickets`
1. 4.1 의 1.
2. `board_read`(모드 `ticket`). `ERR` → 그 코드.
3. 티켓 값 생성 → `SET tk:{<ticket>} <json> NX EX 30`. `NX` 실패(충돌 — 사실상 없음)면 한 번 새로 만들어 다시, 또 실패하면 500.
4. `201 { ticket, expiresAt = Lua now + 30초 }`.

### 4.3 SSE 연결 `GET /realtime/v1/games/today/events?ticket=`
1. `ticket` 이 없거나 `^[A-Za-z0-9_-]{43}$` 가 아니면 → `401 REALTIME_TICKET_INVALID`(Redis 를 부르지 않는다).
2. `GETDEL tk:{<ticket>}` → 없으면 `401 REALTIME_TICKET_INVALID`. 만료는 Redis TTL 이 정한다. (1 · 2 = `RealtimeService.redeemTicket`)
3. `GroupChannelHub.attach(gid)` — 이 노드가 그 그룹을 아직 구독하지 않았으면 `SSUBSCRIBE ch:{<b>}:<gid>` 하고 **구독 확인을 기다린다**. 이 연결은 지금부터 오는 메시지를 연결 버퍼(16칸, 넘치면 가장 오래된 것 버림)에 쌓는다.
4. `board_read`(모드 `stream` · 날짜 = 티켓의 `date`). `ERR` → `detach` 하고 그 코드로 JSON 응답(스트림을 열지 않는다). (`RealtimeService.snapshot` — 3 은 core 가 모르는 노드 안 공유라 컨트롤러가 3 → 4 차례를 지킨다)
5. 스트림을 연다(`200` · `text/event-stream` · `Cache-Control: no-cache` · `X-Accel-Buffering: no`). 첫 프레임 `board.snapshot`(이 유저 기준, `version = V0`, `sentAt` = 4 의 Lua 시각). 이 연결의 `lastVersion = V0`.
6. 버퍼와 그 뒤 메시지를 차례로: `version ≤ lastVersion` 이면 버린다. 아니면 §4.4 표로 프레임을 만들어 보내고 `lastVersion = version`. 3 과 4 사이에 온 변경도 버퍼에 있으므로 빠지지 않는다.
7. 하트비트: 마지막으로 보낸 프레임(이벤트 · ping) 뒤 15초마다 주석 `: ping`(`realtime.heartbeat`) — 버린 메시지는 시계를 되돌리지 않는다. 연결 버퍼 받기와 시간 제한을 `select`(`onReceiveCatching` · `onTimeout`)로 함께 기다린다 — 둘 중 하나만 골라지므로 시간이 다 된 순간 온 메시지도 잃지 않는다.
8. 끝: (a) `rankingFinal = 1` 인 Hash 로 프레임을 보낸 직후(5 의 snapshot 포함) 서버가 스트림을 정상 종료한다. (b) 클라이언트가 끊으면 취소. (c) 그룹 구독이 끊겨 연결 버퍼가 `GroupChannelLostException` 으로 닫히면 경고 로그 `realtime.channel_lost` 를 남기고 정상 종료 — 응답은 이미 `200` 이라 예외로 끝내면 클라이언트에는 같은 끊김이고 서버 오류 로그만 남는다. 클라이언트는 `rankingFinal` 없이 끝난 스트림이라 새 티켓으로 다시 붙는다(RB7). 셋 다 `detach(connection)` — 그 그룹 참조가 0 이면 `SUNSUBSCRIBE`. `detach` 는 취소된 코루틴(`finally`)에서 불러도 끝까지 한다(`NonCancellable`). 요청 헤더 `Last-Event-ID` 는 읽지 않는다.

### 4.4 그룹 채널 메시지 → SSE 이벤트
| 채널 `type` | `hash.result` | SSE 이벤트 |
|---|---|---|
| `group.ranking_final` | 있음 | `group.ranking_final` |
| `group.pending` | 없음 | `group.pending` |
| `group.decided` | 있음 | `group.result` |
| 그 밖(`mission.started` · `solve.applied` · `pass.used` · `member.cheat_failed`) | 없음 | `member.progressed`(`userId` = `hash[<uid>:lid]`) |
| 그 밖 | 있음(08:00 확정 그룹의 유예 풀이) | `group.result` 다시(`rankingFinal` = Hash 값) |
| JSON 파싱 실패 · `hash` 없음 · 모르는 `type`(§2.3 의 7개 밖) · 그룹 Hash 파싱 실패 | — | 버리고 경고 로그 `realtime.bad_message` — `type` 을 늘릴 때는 realtime 을 먼저 배포한다 |
| `group.decided` · `group.ranking_final` 인데 `hash.result` 없음 · `group.ranking_final` 인데 `rankingFinal ≠ 1` · 결과 있는데 `decidedAt` 없음 · 위 「그 밖 · 없음」인데 `uid` 가 없거나 Hash 그룹원이 아님 | — | 버리고 경고 로그 `realtime.bad_message`(`SseFrames.event` 가 null). `lastVersion` 은 그대로 |

- 한 노드가 그룹 메시지 하나를 받으면 파싱은 한 번(`GroupChannelHub`), `BoardView.build` 는 연결마다 한 번이다(그룹 ≤ 10명).
- 프레임 데이터: `member.progressed` · `group.result` · `group.ranking_final` 의 `entries` 는 `BoardView.build(hash, 보는 사람, at, deadlineAt(date))` 이고 `groupStatus` 도 그 값이다. `occurredAt` = `at`, `decidedAt` = `hash.decidedAt`, `group.result` 의 `rankingFinal` = Hash 값, `group.ranking_final` 은 언제나 `true`. `rankingFinal = true` 인 프레임을 보낸 직후 스트림을 끝낸다(§4.3 8 a).
- `GroupChannelHub`: `ConcurrentHashMap<gid, Entry(구독 Job, 연결 목록)>`. 구독 Job 이 받은 메시지를 연결마다의 버퍼(`Channel(16, DROP_OLDEST)`)에 `trySend` 로 나눠 준다 — `SharedFlow` 는 새 구독자가 붙는 시점이 비동기라 「attach 가 돌아온 뒤 메시지는 반드시 받는다」를 보장하기 어렵다. attach · detach 는 gid 별 `Mutex`(gid 해시로 고른 256개 줄무늬 — gid 마다 만들면 지울 때를 정하기 어렵다)로 직렬화하고, 마지막 연결의 detach 는 구독 Job 을 `cancelAndJoin`(= `SUNSUBSCRIBE` 확인)한 뒤 lock 을 놓는다 — 「구독 해제 중 새 연결」 경합에서 구독이 두 개 생기거나 연결이 구독 없이 남지 않는다. 구독이 끊기면(`GroupChannelLostException`) 그 그룹의 연결 버퍼를 모두 그 예외로 닫는다 — 스트림은 그것을 받아 정상 종료한다(§4.3 8 c · I8).
- `SseConnectionRegistry`: 허브가 attach · detach 때 그룹별 연결 수를 올리고 내린다(`connectionCount()` · `connectionCount(gid)`). 게이지 `realtime.connections` 는 전체 수만 낸다 — 노드 확장 기준(HLD platform §9)은 노드 연결 수이고, 그룹별 태그는 그룹 수(수십만)만큼 시계열을 만든다.

### 4.5 보는 사람별 현황판 만들기 `BoardView.build` (HLD §3.1)
입력: 스냅샷 · 보는 사람 uid · 현재 시각(조회는 Lua `now`, 스트림은 메시지 `at`, snapshot 은 Lua `now`) · `deadlineAt`(그날 08:00:00). 출력: `Board(date, groupStatus, version, entries)`.
1. 그룹 상태: `result = SUCCEEDED` → `SUCCEEDED` · `result = FAILED_IMMEDIATE | FAILED_DEADLINE` → `FAILED` · 결과 없고 (`pending = 1` 또는 `now ≥ 08:00:00`) → `PENDING` · 그 밖 → `IN_PROGRESS`.
2. 그룹원마다: `progress = max(p, 0)` · `started = (p ≥ 0)` · `lastSolvedAt = ls` · `passedAtQuestionNo = pn`.
3. 상태: 결과 **없고** `st = CHEAT_FAILED` 이고 그 그룹원 ≠ 보는 사람 → `IN_PROGRESS`. 그 밖은 `st` 그대로.
4. 순위: 결과 있으면 Hash `ranking` 의 순위(`ranking` 이 그룹원 전원의 줄을 담지 않은 비정상 Hash 면 섞지 않고 전원을 아래 계산값으로). 없으면 `core` 랭킹 함수(`Ranking.rank` — LLD deadline-result, 부정 실패는 맨 아래)를 **원래 상태**(3 에서 바꾸기 전)로 계산. 순위는 보는 사람과 상관없다.
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
| Redis 구독 연결 끊김 | 그 노드의 해당 그룹 스트림을 모두 정상 종료한다(구독이 끊긴 사이 메시지를 잃었을 수 있다 · 경고 로그 `realtime.channel_lost`). 클라이언트가 새 티켓으로 다시 붙어 snapshot 을 받는다 | 스트림 종료 |
| `SSUBSCRIBE` · `SUNSUBSCRIBE` 확인이 5초 안에 오지 않음 | 구독 상태를 알 수 없으므로 구독 연결을 버린다 — 위 「끊김」과 같이 그 노드의 그룹 스트림이 모두 닫히고 다음 구독은 새 연결을 연다. 그 attach 는 실패한다 | 스트림 종료 · 연결 실패 |
| 같은 유저가 여러 탭 | 티켓 · 연결을 따로 센다(제한 없음) | — |
| 그룹 Hash 의 그룹원 필드 일부 없음(`ls` · `pn` · `late`) | 없음 = `null` · `false` | — |
| 그룹 Hash 가 틀림(필수 필드 없음 · 값 틀림 — §1 `GroupDaySnapshot`) | 조회는 잡지 않는다(§4.1 4) · 스트림은 메시지를 버린다(§4.4) | 조회 `500` |

## 6. 구현 순서
1. `ErrorCode.NOT_PARTICIPANT` · `Board.kt` 타입 정리(MemberStatus 5 · GroupStatus 4 · `version`) · DTO 정리(`cheatFailed` 삭제) — deadline-result 와 함께 쓰므로 먼저.
2. `GroupDaySnapshot` 파서 · `BoardView.build` + 단위 테스트(ORACLE O20 ~ O22). 랭킹은 deadline-result 의 `Ranking` 을 부르고, 그 전에는 테스트 대역.
3. Lua `board_read` · `RedisGroupBoardRepository` + Testcontainers Redis 테스트(판정 순서 7단계 — ORACLE O3 ~ O7 · O10 · O11).
4. `BoardService.board` · `BoardController` + API 테스트(O1 · O2).
5. 티켓: `RealtimeTicket` · `RedisRealtimeTicketRepository` · `RealtimeService.issueTicket` · `RealtimeTicketController`(O8 · O9 · I1).
6. `GroupChannelCodec` · `RedisGroupEventSubscriber`(SSUBSCRIBE) · `GroupChannelHub`(I3 · I4).
7. `GroupEventStreamController` · `SseFrames` · 하트비트 · 종료 조건 + SSE 통합 테스트(O12 ~ O19 · O23 ~ O29 · I8).
8. `docs/api/openapi.yaml` · `asyncapi.yaml` 를 SPEC 대로 고치고 `RouteSmokeTest` 통과(O30).
9. 지연 측정 테스트(O25 · `DeliveryLatencyTest` — 측정 방법은 ORACLE O25) · 연결 수 게이지 `SseConnectionMetrics`(O31 · `ConnectionMetricsTest`).
