# LLD — game-session

> 판 v4 · 2026-11-11 (v4: T-GS-6 구현 — `worker: prepare-questions` 의 시각 고르기 · 실행 구간 · 켜고 끄기 설정, worker 문제 ID 적재 `QuestionRepository.loadIdsByDifficulty`(Q1 의 `id` · `difficulty` 판)) (v3: T-GS-1 구현 — `GameClock` 의 메서드 · `Question` 에서 `answer` 를 뺌 · 문제 캐시를 `Difficulty` 키로 · 은행 뽑기 결과 타입 · 문제 캐시 적재를 빈 생성으로 · `QuestionRepository.markServed` 는 solve-signal 일감이 치움) (v2: 통합 — 이벤트 Stream 공통 필드(`lid` · `version` · `at`)와 `question.shown` 필드 이름을 HLD platform §4.2 표로 · 채널 메시지를 realtime-board LLD §2.3 모양으로 · 시각 주입 설정 이름 `game.clock-offset` · `app:worker` 뼈대 · persister 소비 루프는 platform 일감) · 짝 문서: [HLD](../hld/game-session.md) · [LLD](../lld/game-session.md) · [SPEC](../spec/game-session.md) · [ORACLE](../oracle/game-session.md) · 근거: [PRD](../prd.md) v10 FR-1 · FR-3 · §5.2 · D6 · D8 · D10 · D14 · D15 · D18 · 바탕: [HLD platform](../hld/platform.md) §2.1 · §4.1 · [LLD platform](platform.md) §2.1 · §3

**세 줄 요약**
- 바뀌는 곳: `app:api` 의 GameController(엔드포인트 3개) · `core` GameService · 문제 캐시 · 은행 뽑기, `infra:redis` 의 Lua `roster` · `start` · `shown`, `infra:rdb` 의 명단 · 출제 이력 쿼리, `app:worker`(모듈 뼈대 · persister 소비 루프는 platform 일감)에 `worker: member`(07:00) · `worker: prepare-questions`(06:30 · 06:50) · `worker: persister` 의 이 기능 이벤트 핸들러 2개.
- 데이터 모델: PostgreSQL 스키마는 바꾸지 않는다(LLD platform §2.1 그대로). Redis 는 platform §4.1 의 키 이름을 그대로 쓰고, 그룹 Hash 의 **필드 이름**(§2.2)과 은행 값 형식(§2.3)을 이 문서가 정한다.
- 구현 순서(§7): 오류 코드 · 계약 → 문제 캐시 · 은행 뽑기 → 명단(`roster`) → 문제은행(`start`) → 게임 정보 → 진입 신호(`shown`) → `app:worker` · `worker: member` → `worker: prepare-questions` → persister 핸들러 → 부하 · 메모리 측정.

## 1. 모듈 · 파일
경로는 모두 `backend/` 아래, 패키지는 `com.delightroom.mission.*` 이다(아래 표는 `…/` 로 줄인다).

| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `support/common/…/common/error/ErrorCode.kt` | 수정 | `NOT_PARTICIPANT(403)` · `QUESTIONS_EXHAUSTED(409)` · `QUESTION_MISMATCH(409)` 추가(api-guide §4 표에 이미 있는 코드) |
| `core/domain/…/core/config/GameProperties.kt` | 수정 | `lateGrace = 3s`(D8) · `bankSizePerDifficulty = 20`(D10) · `prepareTimes: List<LocalTime> = [06:30, 06:50]`(D18 — 오름차순 · 07:00 전만 허용, 기동 때 검사) · `clockOffset: Duration = 0`(시각 주입 — 설정 이름 `game.clock-offset`, HLD platform §8, 테스트 · 시연용. Lua 에는 ARGV `offsetUs`(µs)로 넘긴다 — 모든 기능 공통) |
| `core/domain/…/core/domain/game/GameClock.kt` | 새로 | 게임 날짜 고르기(SPEC GS1) · 그 날짜의 경계 시각(07:00 · 07:00:03 · 08:00 · 06:30 · 06:50)을 `Instant` 와 epoch µs 로. Spring 없는 클래스 `GameClock(properties, clock)` — 빈은 `CoreConfiguration` 이 만든다. 메서드: `now()` · `infoDate(now)`(GS1 게임 정보 — 08:00 부터 다음 날) · `playDate(now)`(문제은행 · 진입 — KST 날짜) · `startAt` · `lateAt` · `deadlineAt` · `prepareAt` · `rosterExpireAt`(다음 날 08:00 — §2.1) 와 µs 판 `startUs` · `lateUs` · `deadlineUs` |
| `core/domain/…/core/domain/question/Question.kt` | 수정 | `Question` 에 내부 ID `internalId`(`questions.id`) · 본문 · 분야(`Category` enum: `CURRENT` · `POLITICS` · `SOCIETY` · `GENERAL`) 추가, 실제 정답 `answer` 는 뺀다(Q1 이 읽지 않는다 — 서버 메모리에 정답을 두지 않는다 · R-A3). `QuestionBank` = 난이도별 내부 ID 목록(`Map<Difficulty, List<Long>>`) |
| `core/domain/…/core/domain/question/QuestionCatalog.kt` | 새로 | 문제 캐시(`api` · `worker` 메모리): `byPk` · `byId` · `idsByDifficulty`(`Difficulty` 키). 기동 시 한 번 적재. 같은 내부 ID · PK 가 둘이면 만들 때 실패 |
| `core/domain/…/core/domain/question/QuestionBankBuilder.kt` | 새로 | 은행 뽑기(순수 함수 — §4.4). `api` 대체 경로와 `worker: prepare-questions` 가 같은 함수를 쓴다. 결과 `BankDraw` = `Built(bank)` · `Exhausted(difficulty)` |
| `core/domain/…/core/domain/roster/DayRoster.kt` | 새로 | 그날 명단(gid · date · name · `members: Map<UserId, LoginId>`) · `isSolo` · `contains` |
| `core/domain/…/core/port/QuestionRepository.kt` | 수정 | `loadAll()` · `loadIdsByDifficulty()`(Q1 의 worker 판 — `id` · `difficulty` 만, T-GS-6) · `findServedIds(userId)` · `findServedIds(userIds)` 로 바꾼다(`findBankFor` · `markServed` 는 지운다 — 기록은 persister). `loadAll` 은 T-GS-1, `findServedIds` 는 쓰는 일감(T-GS-3 · T-GS-6)이 더한다. `markServed` 는 solve-signal 의 TODO 라 그 일감이 지운다 |
| `core/domain/…/core/port/UserRepository.kt` | 수정 | `findMembersJoinedBefore(groupIds, cutoff)` · `findIdsJoinedIn(from, to, afterId, limit)` 추가 |
| `core/domain/…/core/port/GroupRepository.kt` | 수정 | `findPage(afterId, limit)` 추가(`worker: member`) |
| `core/domain/…/core/port/RosterRepository.kt` | 새로 | Redis 명단: `find(gid, date)` · `createIfAbsent(roster)` |
| `core/domain/…/core/port/QuestionBankCache.kt` | 새로 | Redis 은행: `get(uid, date)` · `putIfAbsent(uid, date, bank, expireAt)` · `putAllIfAbsent(…)`(파이프라인) |
| `core/domain/…/core/port/ProgressRepository.kt` | 수정 | `startMission(...)` · `markShown(...)` 추가 — Lua `start` · `shown` 결과(§4.2 · §4.3 출력)를 도메인 결과로 |
| `core/domain/…/core/port/DailyProgressStore.kt` | 새로 | PostgreSQL: `ensurePartitions(date)` · `insertNotStarted(date, rows)` · `applyMissionStarted(evt)` · `recordShown(evt)` |
| `core/domain/…/core/service/GameService.kt` | 수정 | `gameInfo` · `startMission` · `markShown` (§3.1 ~ §3.3) |
| `core/domain/…/core/service/RosterService.kt` | 새로 | 명단 찾기 · 없으면 PostgreSQL 로 만들어 `createIfAbsent`(§3.4) — GameService 와 `worker: member` 가 같이 쓴다 |
| `infra/redis/…/infra/redis/RedisKeys.kt` | 새로(이미 있으면 수정) | 버킷 `b = gid mod 1024` · 키 이름 함수(platform §4.1) |
| `infra/redis/src/main/resources/lua/roster.lua` · `start.lua` · `shown.lua` | 새로 | §4.1 ~ §4.3 |
| `infra/redis/…/infra/redis/RedisProgressRepository.kt` | 수정 | `startMission` · `markShown` = `EVALSHA start` · `shown` |
| `infra/redis/…/infra/redis/RedisRosterRepository.kt` | 새로 | `HGETALL members` · `EVALSHA roster` |
| `infra/redis/…/infra/redis/RedisQuestionBankCache.kt` | 새로 | `GET` · `SET … NX PXAT` |
| `infra/rdb/…/infra/rdb/RdbQuestionRepository.kt` | 수정 | §6 Q1 · Q2 · Q3 |
| `infra/rdb/…/infra/rdb/RdbUserRepository.kt` · `RdbGroupRepository.kt` | 수정 | §6 Q4 · Q5 · Q6 · Q7 |
| `infra/rdb/…/infra/rdb/RdbDailyProgressStore.kt` | 새로 | §6 Q9 · Q10 · Q11 · Q12 |
| `app/api/…/app/api/controller/GameController.kt` | 수정 | `GET /api/v1/games/today` · `POST …/question-bank` · `POST …/shown` |
| `app/api/…/app/api/dto/GameDtos.kt` | 수정 | `ShownRequest` · `ShownResponse` 추가, 기존 응답 DTO 는 그대로(SPEC §2) |
| `app/api/…/app/api/QuestionCatalogLoader.kt` | 새로 | 기동 시 `QuestionCatalog` 적재 — `@Bean` 생성 안에서 Q1 을 끝낸다(싱글턴 생성이 끝나야 웹 서버가 뜨므로 적재 전 요청은 받지 않는다). `schema.sql` · `data.sql` 다음에 돌도록 `infra:rdb` 의 `questionRepository` 빈에 `@DependsOnDatabaseInitialization` |
| `app/worker/build.gradle.kts` · `…/app/worker/WorkerApplication.kt` | (platform 일감이 만든다) | 세 번째 앱 모듈(HLD platform §5 · LLD platform §5 3단계). `@EnableScheduling`, 웹 서버 없음. 이 기능은 잡 · 처리기만 더한다 |
| `app/worker/…/app/worker/member/MemberJob.kt` | 새로 | `worker: member` — 매일 07:00:00 KST (§3.5) |
| `app/worker/…/app/worker/prepare/PrepareQuestionsJob.kt` | 새로 | `worker: prepare-questions` — 06:30:00 · 06:50:00 KST (§3.6). 실행 시각은 `GameClock` 으로 골라 코루틴으로 기다린다(`SmartLifecycle`), 설정 `worker.prepare-questions.enabled`(기본 true) |
| `app/worker/…/app/worker/persister/GameSessionEventHandler.kt` | 새로 | persister 의 `mission.started` · `question.shown` 처리(§3.7). Stream 소비 루프 `StreamPersister` 는 platform 일감이 만든다(LLD platform §5 3단계) |
| `settings.gradle.kts` · `iac/compose.yaml` 의 `worker` 서비스 | (platform 일감이 만든다) | `:app:worker` include · `worker` 서비스(HLD platform §8 표) |
| `docs/api/openapi.yaml` (레포 루트 기준) | 수정 | `POST /api/v1/games/today/shown` · 오류 코드 3개 · 은행 20개 설명(HLD §7). `RouteSmokeTest` 가 라우트와 openapi 를 대조하므로 컨트롤러와 같은 커밋에 |
| `app/api/src/test/…/app/api/gamesession/*Test.kt` 외 | 새로 | ORACLE §0 의 테스트 파일 규약 |

## 2. 데이터 모델
PostgreSQL 은 LLD platform §2.1 의 `users` · `groups` · `questions` · `served_questions` · `daily_progress` 를 그대로 쓴다(스키마 변경 없음). 아래는 이 기능이 읽고 쓰는 Redis 키다. `<b>` = `gid mod 1024`, `<date>` = `YYYY-MM-DD`, `<uid>` = `users.id`, 시각 값은 모두 **epoch 마이크로초 정수 문자열**(Redis `TIME` 기준).

### 2.1 키
| 키 | 타입 | 이 기능이 하는 일 | 만료 |
|---|---|---|---|
| `g:{<b>}:<gid>:<date>:members` | Hash | 만든다(`roster`) · 읽는다 | `EXPIREAT` = 다음 날 08:00:00 KST |
| `g:{<b>}:<gid>:<date>` | Hash (그룹 Hash) | 만든다(`roster`, 2명 이상만) · 그룹원 필드를 바꾼다(`start` · `shown`) | 같음 |
| `day:{<b>}:<date>` | Set | 2명 이상 그룹의 `gid` 를 넣는다(`roster`) | 같음 |
| `ev:{<b>}` | Stream | `XADD` (`mission.started` · `question.shown` — 필드는 HLD platform §4.2) | 없음(persister `XACK` · 트림은 platform) |
| `ch:{<b>}:<gid>` | sharded Pub/Sub | `SPUBLISH` (`start` 만 — 본문은 realtime-board LLD §2.3) | — |
| `bank:{<uid>}:<date>` | String | 만든다(`SET NX`) · 읽는다 | `PXAT` = 그날 08:00:00 KST |

### 2.2 필드
**명단 Hash** `g:{<b>}:<gid>:<date>:members`
| 필드 | 값 | 비고 |
|---|---|---|
| `#size` | 그날 그룹원 수 0~10 | 이 필드가 있으면 「명단 확정됨」. 0 · 1 명도 만든다 — 1 = 1명 그룹 표시(R-G6) |
| `#name` | 그룹명(`groups.name`) | 게임 정보 응답용 |
| `<uid>` | 로그인 아이디 | 그날 그룹원 판정 = `HEXISTS` |

**그룹 Hash** `g:{<b>}:<gid>:<date>` — 이 기능이 만들고(`roster`) 다른 기능(solve-signal · realtime-board · deadline-result)이 같은 이름으로 읽고 쓴다.
| 필드 | 값 | 쓰는 쪽 |
|---|---|---|
| `date` · `gid` | 게임 날짜 · 그룹 PK | `roster` |
| `size` | 그룹원 수 2~10 | `roster` |
| `version` | 0 에서 시작, 그룹이 바뀌어 publish 할 때마다 +1 | `roster`(0) · `start` · solve-signal · deadline-result |
| `<uid>:lid` | 로그인 아이디 | `roster` |
| `<uid>:p` | 진행도 -1 ~ 3 | `roster`(-1) · `start`(0) · solve-signal |
| `<uid>:st` | `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | `roster` · `start` · solve-signal · deadline-result |
| `<uid>:sa` | 미션 시작 시각(µs) — 없으면 시작 전 | `start` |
| `<uid>:late` | `1` · `0` (D8) | `start` |
| `<uid>:cur` | 「이 번호의 지금 문제」 PK(`pk_hash`) — 번호는 언제나 진행도 + 1 | `shown`(고정) · solve-signal(비움) |
| `<uid>:ls` · `<uid>:pn` · `<uid>:pq` · `<uid>:cf` | 마지막 풀이 도착 · 패스 번호 · 넘긴 문제 · 부정 실패 시각 | solve-signal (이 기능은 읽지 않는다) |
| `pending` · `result` · `decidedAt` · `ranking` · `rankingFinal` | 그룹 결과 | solve-signal · deadline-result |

### 2.3 은행 값 · 문제 캐시
| 대상 | 형식 | 크기 |
|---|---|---|
| `bank:{<uid>}:<date>` 값 | JSON `{"1":[<questions.id>…],"2":[…],"3":[…]}` — 난이도마다 1~20개, 배열 순서 = 응답 순서 | 60개 × 7자 안팎 ≈ 0.5 KB(+ 키 · 오버헤드). 250만 명 ≈ 1.5~2.5 GB — ORACLE O32 에서 잰다 |
| `QuestionCatalog` | `byPk: Map<QuestionId, Question>` · `byId: Map<Long, Question>` · `idsByDifficulty: Map<Difficulty, LongArray>`(ID 오름차순) | 문제 수 × (본문 + 해시 2개). 기동 시 `questions` 전체(§6 Q1) |

## 3. 처리 절차
공통: 인증 주체(auth-signup 이 정하는 access token 클레임 — 유저 PK · 로그인 아이디 · 그룹 PK)에서 `uid` · `lid` · `gid` 를 얻는다. **핫 경로(문제은행 · 진입 신호)는 PostgreSQL 을 읽지 않는다** — 명단이 아직 없거나 은행 캐시가 없을 때만 읽는다. 시각 판정은 Lua 안 `TIME` + `offsetUs`(= `game.clock-offset`) 이고, 앱 `Clock` 은 날짜와 경계 시각을 고르는 데만 쓴다.

### 3.1 게임 정보 `GET /games/today`
1. `now = clock.instant() + clockOffset`. 게임 날짜 `D` = `now` 가 KST 로 그 날짜 08:00 전이면 그 날짜, 아니면 다음 날(SPEC GS1).
2. `now ≥ D 07:00` 이면 명단 = `RosterService.findOrCreate(gid, D)`(§3.4). 그룹명 = 명단 `#name`, 그룹원 = 명단의 `<uid>` 필드 값.
3. `now < D 07:00` 이면 명단을 만들지 않고 PostgreSQL 에서 지금까지의 07:00 이전 가입자를 읽는다(§6 Q4 — `gid` 하나, cutoff = `D 07:00`) · 그룹명(§6 Q6).
4. 그룹원을 로그인 아이디 오름차순으로 정렬해 응답한다. `startAt = D 07:00` · `deadlineAt = D 08:00` · `serverTime = now`.

### 3.2 문제은행 = 미션 시작 `POST /games/today/question-bank`
1. `D` = `now` 의 KST 날짜. 경계 `startUs = D 07:00` · `lateUs = D 07:00:03` · `deadlineUs = D 08:00`.
2. `EVALSHA start`(§4.2). 결과가
   - `ERR <code>` → 그 코드로 끝낸다(`GAME_NOT_OPEN` · `SUBMISSION_CLOSED` · `NOT_PARTICIPANT` · `SOLO_GROUP`).
   - `NO_ROSTER` → `RosterService.findOrCreate(gid, D)` 후 `start` 를 **한 번만** 다시 부른다(또 `NO_ROSTER` 면 500 — 일어나지 않아야 한다, ORACLE I4).
   - `OK sa created` → 3 으로.
3. 은행: `GET bank:{<uid>}:<D>`.
   - 있으면 그대로 쓴다(같은 날 재요청 = 같은 은행 — GS7).
   - 없으면: §6 Q2 로 출제 이력 → `QuestionBankBuilder.build(catalog, served, 20)`(§4.4). 한 난이도라도 0개 → `QUESTIONS_EXHAUSTED`(캐시에 넣지 않는다). 아니면 `SET bank:{<uid>}:<D> <json> NX PXAT <D 08:00 ms>`. `NX` 가 실패하면(동시 요청이 먼저 넣음) 다시 `GET` 해 그 값을 쓴다 — 은행은 하나다(ORACLE I2).
4. 내부 ID → `catalog.byId` 로 `questionId(pk_hash)` · 난이도 · 본문 · 분야 · 정답 해시를 채운다. 캐시에 없는 ID(문제 삭제 — 운영상 없음)는 건너뛴다.
5. 응답: `missionStartedAt = sa`(µs → `Instant`, `+09:00`), 문제 목록(난이도 오름차순, 그 안은 은행 배열 순서).
- 미션 시작(2)이 은행(3)보다 먼저다(HLD §3 순서) — 시작 시각에 은행 계산 시간이 끼지 않아 지각 판정이 공정하다. 그 대가로 `QUESTIONS_EXHAUSTED` 인 유저도 「시작」으로 남는다(§5).

### 3.3 진입 신호 `POST /games/today/shown`
1. 본문 검증: `questionNo` ∈ {1, 2, 3}, `questionId` ~ `^[0-9a-f]{64}$`. 어기면 `INVALID_REQUEST`(Lua 를 부르지 않는다).
2. `q = catalog.byPk[questionId]`. `qok = (q != null && q.difficulty == questionNo) ? 1 : 0` — 여기서 바로 거부하지 않고 Lua 에 넘긴다(HLD §3 판정 순서: 시간 · 그룹원 · 상태가 먼저).
3. `EVALSHA shown`(§4.3). `NO_ROSTER` 면 §3.2-2 와 같이 명단을 만들고 한 번 다시 부른다.
4. 응답: `{ questionNo, questionId, progress, missionStatus }` — Lua 가 돌려준 진행도(0~2) · 상태.

### 3.4 명단 찾기 · 만들기 (`RosterService.findOrCreate`)
1. `HGETALL g:{<b>}:<gid>:<D>:members` — `#size` 가 있으면 그것이 명단이다.
2. 없으면 §6 Q4(`gid` 하나, cutoff `D 07:00`) · §6 Q6 → `EVALSHA roster`(§4.1). `roster` 는 「없을 때만 만들기」라 `worker: member` · 다른 `api` 요청과 겹쳐도 한 번만 생긴다(ORACLE I4).
3. `roster` 가 `created = 1` 이고 그룹원이 2명 이상이면 §6 Q10 으로 `daily_progress` 에 -1 행을 넣는다(`ON CONFLICT DO NOTHING`). 실패해도 요청은 계속한다 — `worker: member` 가 모든 그룹에 다시 넣는다(§3.5-4).
4. `D 07:00` 전에는 이 함수를 부르지 않는다(§3.1-3) — 07:00 전 명단은 확정이 아니다.

### 3.5 `worker: member` — 매일 07:00:00 KST
1. `D` = 오늘. §6 Q9 로 `D` · `D+1` 파티션을 만든다(`IF NOT EXISTS` — 이미 있으면 그대로).
2. 그룹을 PK 순서로 500개씩 읽는다(§6 Q7 — keyset `id > :after`).
3. 그 500개 그룹의 07:00 이전 가입자를 한 번에 읽는다(§6 Q4 — `group_id = ANY(:gids)`, 인덱스 `users_group_joined_idx`).
4. 그룹마다 `EVALSHA roster` 를 파이프라인으로(동시 64개) 보낸다. 그리고 그룹원이 2명 이상인 그룹의 그룹원 전부를 §6 Q10 으로 한 번에 넣는다 — `created` 와 상관없이 넣어 `api` 가 먼저 만든 그룹의 -1 행도 채운다.
5. 마지막 페이지까지 2~4 를 반복하고 요약 한 줄(그룹 수 · 2명 이상 · 1명 · 0명 · 소요)을 stdout 에 남긴다.
- 중간에 죽으면 같은 잡을 다시 돌린다 — 모든 단계가 멱등(`IF NOT EXISTS` · `roster` 없을 때만 · `ON CONFLICT DO NOTHING`). 그동안 요청은 §3.4 로 명단을 그 자리에서 만든다.

### 3.6 `worker: prepare-questions` — 06:30:00 · 06:50:00 KST (D18)
1. `D` = 오늘. 대상 구간: 06:30 실행 = `joined_at < D 06:30`, 06:50 실행 = `D 06:30 ≤ joined_at < D 06:50`. 그룹 인원과 상관없다.
2. 잡 시작 때 문제 캐시(§6 Q1 의 `id` · `difficulty` 만)를 한 번 읽는다.
3. 대상 유저 ID 를 PK 순서로 1,000개씩 읽는다(§6 Q5).
4. 그 1,000명의 출제 이력을 한 번에 읽는다(§6 Q3) → 유저마다 `QuestionBankBuilder.build`(§4.4). 한 난이도라도 0개면 그 유저는 만들지 않는다(요청 때 `QUESTIONS_EXHAUSTED` — HLD §3).
5. `SET bank:{<uid>}:<D> <json> NX PXAT <D 08:00 ms>` 를 파이프라인으로 보낸다. 이미 있으면(06:30 에 만든 것 · `api` 가 만든 것) 두지 않는다.
6. 요약 한 줄(대상 · 만듦 · 이미 있음 · 고갈 · 소요 · 초당 유저)을 stdout 에.
- 실행 구간 일반형: `game.prepare-times` 의 `i` 번째 실행 = `prepareAt[i-1] ≤ joined_at < prepareAt[i]`(첫 실행은 아래 끝 없음). 위 1 은 기본값 [06:30, 06:50] 의 경우다.
- 시각 고르기: `GameClock.now()`(`game.clock-offset` 포함) 다음에 오는 `prepareAt` 까지 코루틴으로 기다렸다가 돈다(`D` = 그 시각의 `playDate`). 실행이 끝나면 다음 시각을 다시 고른다 — 기동 때 이미 지난 시각은 돌지 않는다(정합성은 아래 대체 경로가 지킨다). 버린 대안: Spring `@Scheduled(cron)` — 시각 주입을 모르고, 기본 스케줄러 스레드가 하나라 07:00 을 넘긴 06:50 실행이 `worker: member` 를 막는다.
- 설정 `worker.prepare-questions.enabled`(기본 true) — false 면 기다리는 루프를 띄우지 않는다(잡을 직접 부르는 테스트). 잡 본문 `run(D, i)` 은 루프와 상관없이 부를 수 있다.
- 늦거나 일부 실패해도 정합성은 같다 — 요청 때 `api` 가 같은 함수로 만든다(§3.2-3). 07:00 이 지나도 끝나지 않은 06:50 실행은 계속 돈다(`NX` 라 `api` 가 만든 은행을 덮지 않는다).

### 3.7 `worker: persister` 핸들러 (이 기능의 이벤트)
| 이벤트 | 처리 | 쿼리 |
|---|---|---|
| `mission.started` | `daily_progress` 를 진행도 0 · `IN_PROGRESS` · 시작 시각 · 지각으로 멱등 upsert | §6 Q11 |
| `question.shown` | `served_questions` 에 `SHOWN` 행 삽입. 충돌했고 기존 행의 (`game_date`, `question_no`)가 이벤트와 다르면 = 이미 낸 문제가 다시 나감 → 운영 로그(HLD §4.1) 한 줄. 같으면 같은 이벤트의 재처리라 로그 없음 | §6 Q12 |

운영 로그(WARN, 한 줄 JSON, stdout): `{"event":"question.served_duplicate","date":"<date>","loginId":"<lid>","questionNo":<no>,"questionId":"<pk>"}`

## 4. Lua 스크립트
공통: `t = TIME` 을 µs 로 바꾼 값 + `offsetUs`(`game.clock-offset` 을 µs 로). 반환은 배열 — 첫 칸이 `OK` · `ERR` · `NO_ROSTER`. 버킷이 같은 키만 쓰므로(platform §4.1) Cluster 에서도 한 슬롯이다. `SPUBLISH` 채널은 `ARGV` 로 받는다(키가 아님).

### 4.1 `roster.lua` — 명단 만들기(없을 때만)
| 입력 | 값 |
|---|---|
| KEYS | `[1]` `g:{<b>}:<gid>:<D>:members` · `[2]` `g:{<b>}:<gid>:<D>` · `[3]` `day:{<b>}:<D>` |
| ARGV | `[1]` `D` · `[2]` `gid` · `[3]` 그룹명 · `[4]` 만료 epoch 초(다음 날 08:00 KST) · `[5]` n · 이어서 `uid₁, lid₁, …, uidₙ, lidₙ` |

1. `EXISTS KEYS[1]` = 1 → `{OK, 0}`(이미 있음 — 아무것도 바꾸지 않는다).
2. `HSET KEYS[1] #size n #name <name> uid₁ lid₁ …` · `EXPIREAT`.
3. n ≥ 2 이고 `EXISTS KEYS[2]` = 0 이면(재적재가 먼저 올린 그룹 Hash 를 -1 로 덮지 않는다): `HSET KEYS[2] date D gid <gid> size n version 0` 과 그룹원마다 `<uid>:lid` · `<uid>:p -1` · `<uid>:st NOT_STARTED` · `EXPIREAT`. `SADD KEYS[3] gid` · `EXPIREAT`.
4. 출력 `{OK, 1}`.
- 1명 · 0명 그룹은 명단 Hash 만 있고 그룹 Hash · 그날 그룹 목록에는 없다(HLD §5 — 결과가 없어야 한다).

### 4.2 `start.lua` — 미션 시작
| 입력 | 값 |
|---|---|
| KEYS | `[1]` 그룹 Hash · `[2]` 명단 Hash · `[3]` `ev:{<b>}` |
| ARGV | `[1]` uid · `[2]` `startUs` · `[3]` `lateUs` · `[4]` `deadlineUs` · `[5]` `offsetUs` · `[6]` 채널 `ch:{<b>}:<gid>` · `[7]` `D` · `[8]` gid |

판정 순서(처음 걸리는 것으로 끝낸다):
1. `t < startUs` → `{ERR, GAME_NOT_OPEN}`. `t ≥ deadlineUs` → `{ERR, SUBMISSION_CLOSED}`.
2. `HGET KEYS[2] #size` 없음 → `{NO_ROSTER}`.
3. `HEXISTS KEYS[2] uid` = 0 → `{ERR, NOT_PARTICIPANT}`.
4. `#size` = 1 → `{ERR, SOLO_GROUP}`.
5. `<uid>:p` ≠ -1 → 바꾸지 않고 `{OK, <uid>:sa, 0, p, st}`(재접속 · 재시도 · 동시 요청의 두 번째).
6. 반영: `<uid>:p = 0` · `<uid>:st = IN_PROGRESS` · `<uid>:sa = t` · `<uid>:late = (t > lateUs) ? 1 : 0` · `HINCRBY version 1`.
7. `XADD KEYS[3] * type mission.started date D gid <gid> uid <uid> lid <lid> version <v> at t startedAt t late <0|1>` → id `e`(필드 정본: HLD platform §4.2).
8. `SPUBLISH ARGV[6] <msg>` — `msg` = `{"type":"mission.started","streamId":e,"version":v,"date":D,"gid":gid,"uid":uid,"at":t,"hash":HGETALL KEYS[1]}`(realtime-board LLD §2.3 정본, 시각 · id 는 문자열). 현황판에서 그 줄이 「미접속」→「진행 중」.
9. 출력 `{OK, t, 1, 0, IN_PROGRESS}`.

### 4.3 `shown.lua` — 진입 신호
| 입력 | 값 |
|---|---|
| KEYS | `[1]` 그룹 Hash · `[2]` 명단 Hash · `[3]` `ev:{<b>}` |
| ARGV | `[1]` uid · `[2]` questionNo · `[3]` questionId(pk) · `[4]` 내부 ID(`qok=0` 이면 빈 값) · `[5]` `qok` · `[6]` `startUs` · `[7]` `deadlineUs` · `[8]` `offsetUs` · `[9]` `D` · `[10]` gid |

판정 순서:
1. `t < startUs` → `GAME_NOT_OPEN`. `t ≥ deadlineUs` → `SUBMISSION_CLOSED`.
2. 명단 없음 → `NO_ROSTER`. 그룹원 아님 → `NOT_PARTICIPANT`. `#size` = 1 → `SOLO_GROUP`.
3. `st = NOT_STARTED` → `MISSION_NOT_STARTED`. `st ∈ {SUCCEEDED, CHEAT_FAILED}` → `MISSION_ALREADY_FINISHED`. (`FAILED` 는 08:00 이후에만 생겨 1 에서 걸린다.)
4. `qok = 0` → `QUESTION_MISMATCH`(없는 PK · 난이도 ≠ 번호).
5. `questionNo ≠ p + 1` → `QUESTION_OUT_OF_ORDER`.
6. `cur = <uid>:cur`
   - 비어 있음 → `HSET <uid>:cur pk`, `XADD KEYS[3] * type question.shown date D gid <gid> uid <uid> lid <lid> version <지금 값> at t questionNo <no> questionId <pk> qid <내부 ID> shownAt t` → `{OK, p, st, 1}`(필드 정본: HLD platform §4.2 — `version` 은 올리지 않은 지금 값).
   - `cur = pk` → 바꾸지 않고 `{OK, p, st, 0}`(멱등 — 이벤트 없음).
   - 그 밖 → `QUESTION_MISMATCH`(바꾸는 길은 패스뿐 — solve-signal).
- 진입 신호는 현황판에 보이는 값을 바꾸지 않으므로 `version` 을 올리지 않고 publish 하지 않는다.
- 출제 이력 중복은 여기서 보지 않는다(D15) — persister 가 본다(§3.7).

### 4.4 은행 뽑기 (`QuestionBankBuilder.build`, Kotlin — Lua 아님)
입력: `catalog.idsByDifficulty` · 이 유저의 출제 이력 ID 집합 `served` · `size = 20` · 난수(`SecureRandom` 이 아닌 `ThreadLocalRandom` — 보안 성질 불필요).
1. 난이도 1 · 2 · 3 마다 후보 = `idsByDifficulty[d] − served`.
2. 후보가 0 → 이 유저는 고갈(`Exhausted`).
3. 후보 ≤ 20 → 전부(섞어서), 아니면 부분 Fisher–Yates 로 20개.
4. 출력 `BankDraw.Built(QuestionBank({1: […], 2: […], 3: […]}))` · 고갈은 `BankDraw.Exhausted(가장 낮은 고갈 난이도)` — 고갈이면 은행을 만들지 않는다.
- 버린 대안: SQL `ORDER BY random() LIMIT 20`(난이도마다 `questions` 전체 정렬 — 유저마다 3번 · 250만 명) 대신, 문제 ID 는 메모리에 있으므로 PostgreSQL 에는 출제 이력만 묻는다(PK 앞부분 `user_id` 로 인덱스 범위 조회).

## 5. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| 07:00:00.000000 직전 · 08:00:00.000000 정각 | Lua `t` 로 판정 — `startUs ≤ t < deadlineUs` 만 통과 | `GAME_NOT_OPEN` · `SUBMISSION_CLOSED` |
| 시작 시각이 정확히 07:00:03.000000 | `t > lateUs` 만 지각 | 지각 아님 |
| 같은 유저의 문제은행 동시 요청 · 재시도 | `start` 5 — 처음 하나만 바꾼다. 은행은 `SET NX` 로 하나 | 모두 같은 `missionStartedAt` · 같은 은행 |
| `worker: member` 가 아직 그 그룹에 오지 않음 | §3.4 — 그 자리에서 `roster` | 정상 |
| `worker: member` 와 `api` 가 같은 그룹 명단을 동시에 만듦 | `roster` 1 — 먼저 실행된 쪽만 만든다. 둘 다 같은 규칙(`joined_at < 07:00`, 탈퇴 · 이동 없음)이라 내용도 같다 | 정상 |
| 은행 캐시 없음(06:50 이후 가입 · 잡 실패 · Redis 재시작) | §3.2-3 대체 경로 — 그때의 출제 이력(오늘 이미 낸 문제 포함)을 뺀다 | 정상 |
| 고갈(한 난이도 0개) | 캐시에 넣지 않는다. 미션 시작(진행도 0)은 이미 반영돼 남는다 — 운영 문제(prd §10 문제 보충) | `409 QUESTIONS_EXHAUSTED` |
| 은행 ID 가 문제 캐시에 없음 | 그 ID 를 건너뛴다(문제는 지우지 않는 운영 — 생기면 `api` 재기동 필요) | 정상(개수 줄어듦) |
| 새로 넣은 문제 | 문제 캐시는 기동 시 한 번 적재 — `api` · `worker` 재기동 뒤부터 은행에 들어간다 | — |
| 진입 신호: 없는 PK · 난이도 ≠ 번호 · 다른 지금 문제 | `shown` 4 · 6 | `409 QUESTION_MISMATCH` |
| 진입 신호: 번호 ≠ 진행도 + 1(이미 푼 번호의 재시도 포함) | `shown` 5 | `409 QUESTION_OUT_OF_ORDER` — 클라이언트는 문제은행 · 현황판으로 다시 맞춘다 |
| 진입 신호: 이미 낸 문제(평생 이력) | 거부하지 않는다(D15) — persister 운영 로그 | `200` |
| 07:00 전 게임 정보 | PostgreSQL 에서 지금까지의 가입자(§6 Q4). 06:59 몰림이 PostgreSQL 에 바로 간다 — 부하는 ORACLE O35 에서 잰다 | `200` |
| 가입 트랜잭션이 07:00 직전에 시작해 07:00 뒤에 커밋 | `joined_at` 은 트랜잭션 시작 시각이라 07:00 전 값인데 `worker: member` 가 못 볼 수 있다. ms 단위 창 — 그 유저는 그날 `NOT_PARTICIPANT` 가 된다 | 판정 밖(ORACLE §3) |
| `daily_progress` 오늘 파티션 없음 | `worker: member` 1 · persister 시작 시 `IF NOT EXISTS` 로 만든다 | — |
| Redis 가 비어 재시작 | 그룹 Hash 재적재는 platform(LLD platform §4). 명단은 §3.4 로 다시 만들어지고, `roster` 3 은 이미 있는 그룹 Hash 를 건드리지 않는다 | — |

## 6. PostgreSQL 쿼리
| id | 쓰는 곳 | SQL |
|---|---|---|
| Q1 | 문제 캐시 적재(`api` 기동 · `worker: prepare-questions` 시작) | `SELECT id, pk_hash, difficulty, category, body, answer_hash FROM questions ORDER BY id` (worker 는 `SELECT id, difficulty FROM questions ORDER BY id` — `loadIdsByDifficulty`) |
| Q2 | 은행 대체 경로(유저 한 명) | `SELECT question_id FROM served_questions WHERE user_id = $1` |
| Q3 | `worker: prepare-questions`(1,000명) | `SELECT user_id, question_id FROM served_questions WHERE user_id = ANY($1::bigint[])` |
| Q4 | 명단(그룹 1개 또는 500개) | `SELECT group_id, id, login_id FROM users WHERE group_id = ANY($1::bigint[]) AND joined_at < $2 ORDER BY group_id, login_id` — `$2` = `D 07:00 KST` |
| Q5 | `worker: prepare-questions` 대상 | 06:30: `SELECT id FROM users WHERE id > $1 AND joined_at < $2 ORDER BY id LIMIT 1000` · 06:50: `… AND joined_at >= $2 AND joined_at < $3 …` (PK 순서로 한 번 훑는다 — `joined_at` 단독 인덱스는 만들지 않는다) |
| Q6 | 그룹명 | `SELECT name FROM groups WHERE id = $1` |
| Q7 | `worker: member` 그룹 페이지 | `SELECT id, name FROM groups WHERE id > $1 ORDER BY id LIMIT 500` |
| Q9 | 파티션(`D` · `D+1`, 4개 테이블) | `CREATE TABLE IF NOT EXISTS daily_progress_<yyyymmdd> PARTITION OF daily_progress FOR VALUES FROM ('<d>') TO ('<d+1>')` — `solve_signals` · `group_results` · `group_result_members` 도 같은 꼴 |
| Q10 | -1 행 | `INSERT INTO daily_progress (game_date, user_id, group_id, progress, status) SELECT $1, u, g, -1, 'NOT_STARTED' FROM unnest($2::bigint[], $3::bigint[]) AS t(u, g) ON CONFLICT (game_date, user_id) DO NOTHING` |
| Q11 | `mission.started` | `INSERT INTO daily_progress (game_date, user_id, group_id, progress, status, started_at, is_late, updated_at) VALUES ($1, $2, $3, 0, 'IN_PROGRESS', $4, $5, now()) ON CONFLICT (game_date, user_id) DO UPDATE SET progress = GREATEST(daily_progress.progress, 0), status = CASE WHEN daily_progress.status = 'NOT_STARTED' THEN 'IN_PROGRESS' ELSE daily_progress.status END, started_at = COALESCE(daily_progress.started_at, EXCLUDED.started_at), is_late = COALESCE(daily_progress.is_late, EXCLUDED.is_late), updated_at = now()` |
| Q12 | `question.shown` | `WITH ins AS (INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) VALUES ($1, $2, $3, $4, 'SHOWN', $5) ON CONFLICT (user_id, question_id) DO NOTHING RETURNING 1) SELECT (SELECT count(*) FROM ins) AS inserted, s.game_date, s.question_no FROM served_questions s WHERE s.user_id = $1 AND s.question_id = $2` — 같은 문장의 바깥 `SELECT` 는 방금 넣은 행을 보지 않으므로, 행이 나오면 = 이미 있던 행(충돌). 그 (`game_date`, `question_no`) ≠ (`$3`, `$4`) 면 운영 로그 |

- 접근 방식(R2DBC · JDBC)은 `infra:rdb` 가 아직 정하지 않았다 — 위 SQL 은 어느 쪽이든 그대로 쓴다.
- 시각 인자는 모두 `timestamptz`(KST 오프셋을 붙인 `Instant`). µs 값은 `to_timestamp(µs / 1e6)` 이 아니라 앱에서 `Instant` 로 바꿔 넘긴다.

## 7. 구현 순서
1. 계약 · 바탕: `ErrorCode` 3개 · `openapi.yaml` 에 `shown` 과 오류 코드 · `GameProperties`(`lateGrace` · `bankSizePerDifficulty` · `prepareTimes` · `clockOffset`) · `GameClock`(GS1 · 경계 시각) + 단위 테스트.
2. 문제 캐시: `Question` · `QuestionCatalog` · `QuestionCatalogLoader` · Q1 + 시드 데이터로 적재 테스트.
3. 은행 뽑기: `QuestionBankBuilder`(§4.4) + 단위 테스트(20개 · 모자람 · 고갈 · 출제 이력 제외).
4. 명단: `RedisKeys` · `roster.lua` · `RedisRosterRepository` · Q4 · Q6 · Q10 · `RosterService` + Redis · PostgreSQL 통합 테스트.
5. 문제은행: `start.lua` · `RedisQuestionBankCache` · Q2 · `GameService.startMission` · `POST /question-bank`.
6. 게임 정보: `GameService.gameInfo` · `GET /games/today`.
7. 진입 신호: `shown.lua` · `GameService.markShown` · `POST /shown` · DTO.
8. `worker: member`(Q7 · Q4 · Q9 · Q10) — `app:worker` 모듈 뼈대(`settings.gradle.kts` · compose)는 platform 일감이 먼저 만든다(LLD platform §5 3단계).
9. `worker: prepare-questions`(Q5 · Q3 · 파이프라인 `SET NX`).
10. persister 핸들러 `mission.started`(Q11) · `question.shown`(Q12 · 운영 로그) — platform 일감의 persister 소비 루프(LLD platform §5 3단계) 다음.
11. 부하 · 메모리 측정(ORACLE O32 ~ O35).
