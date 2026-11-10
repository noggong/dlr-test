# LLD — solve-signal

> 판 v2 · 2026-11-11 (v2: 통합 — 시각 주입을 `game.clock-offset`(`GameProperties.clockOffset`) · Lua ARGV `offsetUs` 로 · Stream 공통 필드 `loginId` → `lid` 를 HLD platform §4.2 표로 · 채널 메시지를 realtime-board LLD §2.3 모양(`at` 더함 · `members` 뺌)으로 · `app:worker` 뼈대 · `StreamPersister` 소비 루프는 platform 일감) · 짝 문서: [HLD](../hld/solve-signal.md) · [LLD](../lld/solve-signal.md) · [SPEC](../spec/solve-signal.md) · [ORACLE](../oracle/solve-signal.md) · 근거: [PRD](../prd.md) v10 FR-4 · §5.4 ~ §5.7 · NFR-1 ~ NFR-3 · D1 · D2 · D9 · D14 · D17 · 바탕: [HLD platform](../hld/platform.md) §2.1 · §4.1 · [LLD platform](platform.md) §2.1 · §3

**세 줄 요약**
- 바뀌는 곳: `app:api` SignalController · `core` SignalService · `infra:redis` Lua `solve` · `pass` · `fail`(+ 공용 `lib`) · `app:worker`(모듈 뼈대 · 소비 루프는 platform 일감)에 `worker: persister` 처리기 4개(`solve.applied` · `pass.used` · `member.cheat_failed` · `group.decided`).
- 데이터 모델: 새 테이블은 없다. Redis 그룹 Hash 의 그룹원 칸(`<uid>:p` 등)과 그룹 칸(`version` · `result` …)을 Lua 가 읽고 쓰며, Stream `ev:{<b>}` 메시지 4종이 PostgreSQL(LLD platform §3)로 간다.
- 구현 순서: 오류 코드 · 설정 → Lua 공용 · `solve` → `pass` · `fail` → 포트 · 서비스 · 컨트롤러 → `worker: persister` 처리기 → 계약(openapi) · 스모크 테스트 정리.

## 1. 모듈 · 파일
패키지 접두 `com.delightroom.mission`. 경로는 `backend/` 아래.

| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `support/common/src/main/kotlin/…/common/error/ErrorCode.kt` | 수정 | `QUESTION_MISMATCH(409)` · `NOT_PARTICIPANT(403)` 를 더한다(api-guide §4 표 그대로) |
| `core/domain/src/main/kotlin/…/core/config/GameProperties.kt` | 수정 | `bucketCount = 1024`(그룹 버킷 `b = gid mod 1024`) · `clockOffset: Duration = 0`(시각 주입 — 설정 이름 `game.clock-offset`, 테스트 · 로컬 데모용, HLD platform §8. 필드는 game-session 이 더한다 — 먼저 하는 쪽이 넣는다. Lua 에는 ARGV `offsetUs`(µs)로) |
| `core/domain/src/main/kotlin/…/core/domain/progress/MissionProgress.kt` | 수정 | `solve` · `pass` · `failByCheat` 도메인 메서드를 지운다 — 판정은 Lua 한 벌만 둔다(§3). `SolveOutcome` 을 `SignalResult` 로 바꾼다 |
| `core/domain/src/main/kotlin/…/core/domain/progress/SignalResult.kt` | 새로 | Lua 반환을 옮긴 값: `outcome`(APPLIED · REPLAY) · `progress`(-1~3) · `missionStatus` · `groupStatus` · `version` · `arrivedAt` · `passedAtQuestionNo` |
| `core/domain/src/main/kotlin/…/core/domain/progress/SignalCommand.kt` | 새로 | Lua 에 넘길 값: `SolveCommand` · `PassCommand` · `FailCommand`(그룹 · 버킷 · 날짜 · 시간 창 마이크로초 포함) |
| `core/domain/src/main/kotlin/…/core/port/ProgressRepository.kt` | 수정 | `applySolve(SolveCommand)` · `applyPass(PassCommand)` · `applyCheatFail(FailCommand)` 를 더한다. 반환은 `SignalResult` 또는 `SignalRejection(code, arrivedAt)` |
| `core/domain/src/main/kotlin/…/core/service/SignalService.kt` | 수정 | 그룹 · 버킷 · 날짜 · 시간 창 계산 → 포트 호출 → 거부 코드를 `BusinessException` 으로(§3.4) |
| `app/api/src/main/kotlin/…/app/api/controller/SignalController.kt` | 수정 | 세 엔드포인트. 인증 주체에서 uid · gid 를 꺼내 서비스 호출 |
| `app/api/src/main/kotlin/…/app/api/dto/SignalDtos.kt` | 수정 | 검증 규칙(SPEC §2) · `PassResponse` 에 `questionNo` · `progress` 추가 · 상태는 enum 문자열 |
| `app/api/src/main/kotlin/…/app/api/web/GlobalExceptionHandler.kt` | 수정 | DTO 검증 실패(형식 · 범위 · 시각 오프셋 없음)를 `400 INVALID_REQUEST` 로 |
| `infra/redis/src/main/resources/lua/lib/common.lua` | 새로 | 시각(`now()` = `TIME` + 오프셋), 그룹 상태 계산, 그룹원 칸 읽기 · 쓰기, 이벤트 XADD · SPUBLISH 도우미 |
| `infra/redis/src/main/resources/lua/lib/decide.lua` | 새로 | 그룹 판정(HLD §4.1) — `solve` · `fail` 공용 |
| `infra/redis/src/main/resources/lua/lib/ranking.lua` | 새로(**deadline-result 소유**) | 랭킹 비교 함수(deadline-result HLD §4). 이 기능은 부르기만 한다 — 먼저 구현하는 쪽이 만들고 다른 쪽은 그대로 쓴다 |
| `infra/redis/src/main/resources/lua/solve.lua` · `pass.lua` · `fail.lua` | 새로 | §3.1 ~ §3.3 |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisKeys.kt` | 새로 | 키 이름 만들기(§2.1 — platform §4.1 과 같은 이름) |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisScripts.kt` | 새로 | `lib/*.lua` + 스크립트 본문을 이어 붙여 `SCRIPT LOAD` → `EVALSHA`, `NOSCRIPT` 면 다시 적재 후 1회 재시도 |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisProgressRepository.kt` | 수정 | `applySolve` · `applyPass` · `applyCheatFail` — `RedisScripts` 호출 · 반환 배열 해석(§3.5) |
| `settings.gradle.kts` · `app/worker/build.gradle.kts` · `app/worker/src/main/kotlin/…/app/worker/WorkerApplication.kt` | (platform 일감이 만든다) | 세 번째 앱 모듈(HLD platform §5 · LLD platform §5 3단계). 다른 worker(`deadline` · `member` · `prepare-questions`)도 여기 산다 |
| `app/worker/src/main/kotlin/…/app/worker/persister/StreamPersister.kt` | (platform 일감이 만든다 — LLD platform §5 3단계) | 버킷 Stream `XREADGROUP` 소비 루프 · 종류별 처리기 호출 · 커밋 뒤 `XACK`. 이 기능은 처리기를 등록만 한다 |
| `app/worker/src/main/kotlin/…/app/worker/persister/SignalEventHandlers.kt` | 새로 | 이 기능의 처리기 4개(§3.6) |
| `infra/rdb/src/main/kotlin/…/infra/rdb/RdbSignalEventRepository.kt` | 새로 | §2.3 SQL(접근 방식 R2DBC/JDBC 는 `infra:rdb` 가 정한 것을 따른다) |
| `docs/api/openapi.yaml` (레포 루트) | 수정 | HLD §7 의 차이 반영 — `QUESTION_MISMATCH` · `NOT_PARTICIPANT` · 멱등 `200` 설명 · `PassResponse` 필드 |
| `app/api/src/test/kotlin/…/app/api/RouteSmokeTest.kt` | 수정 | 구현된 세 오퍼레이션을 「501 기대」 목록에서 뺀다 |
| 테스트 파일 | 새로 | [ORACLE](../oracle/solve-signal.md) §0 의 이름 규약 |

## 2. 데이터 모델
새 테이블 · 새 Redis 키는 없다. 이 기능이 읽고 쓰는 것만 적는다.

### 2.1 Redis 키
`b = gid mod 1024`, `<date>` = KST 게임 날짜 `YYYY-MM-DD`, `<uid>` = `users.id`(내부 PK), `<gid>` = `groups.id`.

| 키 | 타입 | 이 기능 | 만드는 쪽 |
|---|---|---|---|
| `g:{<b>}:<gid>:<date>` | Hash — 그룹 Hash | 읽기 · 쓰기 | `worker: member` · Lua `start`(game-session) |
| `g:{<b>}:<gid>:<date>:members` | Hash — 그날 명단: `#size`(그룹원 수 — 있으면 「명단 확정됨」, 1 = 1명 그룹) · `<uid> → <loginId>` | 읽기 | game-session Lua `roster`(LLD game-session §2.2) |
| `ev:{<b>}` | Stream | `XADD` | — |
| `ch:{<b>}:<gid>` | sharded Pub/Sub 채널 | `SPUBLISH` | — |

**그룹 Hash 칸** (platform §4.1 의 필드 — 이름은 [LLD game-session](game-session.md) §2.2 를 따른다)
| 칸 | 값 | 이 기능 |
|---|---|---|
| `size` | 그룹원 수 2~10 | 읽기 |
| `<uid>:lid` | 로그인 아이디 | 읽기(이벤트 `lid` · 랭킹) |
| `<uid>:st` | 그룹원 상태 `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 읽기 · 쓰기(`SUCCEEDED` · `CHEAT_FAILED`) |
| `<uid>:p` | 진행도 -1 ~ 3 | 읽기 · 쓰기(+1) |
| `<uid>:sa` | 시작 시각(epoch 마이크로초) | 읽기(랭킹) |
| `<uid>:late` | 지각 `0` · `1` | 읽기(랭킹) |
| `<uid>:ls` | 마지막 풀이 도착 시각(epoch 마이크로초) | 쓰기 |
| `<uid>:pn` | 패스 번호 1~3(없으면 칸 없음) | 읽기 · 쓰기 |
| `<uid>:pq` | 패스로 넘긴 문제 PK(hex 64) | 읽기 · 쓰기 |
| `<uid>:cur` | 지금 문제 PK — 언제나 번호 `<uid>:p + 1` 의 문제(game-session 의 `shown` 이 채우고 여기서 비운다) | 읽기 · 지우기 |
| `<uid>:cf` | 부정 실패 시각(epoch 마이크로초) | 쓰기 |
| `version` | 정수, 그룹이 바뀔 때마다 +1 — SSE `version` | `HINCRBY 1` |
| `pending` | `1` = 08:00 판정 미룸(deadline-result) | 읽기 · 판정 때 지우기 |
| `result` | `SUCCEEDED` · `FAILED_IMMEDIATE` · `FAILED_DEADLINE` | `HSETNX` |
| `decidedAt` | 결과 확정 시각(epoch 마이크로초) | 쓰기 |
| `ranking` | 랭킹 JSON 배열(§2.2) | 쓰기(결과 확정 · 확정 뒤 유예 반영) |
| `rankingFinal` | `0` · `1` | 쓰기(결과 확정 때) |

### 2.2 이벤트 Stream 메시지 (`XADD ev:{<b>} * …`)
모든 메시지의 공통 칸(정본 HLD platform §4.2): `type` · `date` · `gid` · `uid`(group.decided 는 마지막으로 바꾼 유저) · `lid` · `version` · `at`(Redis 시각, epoch 마이크로초). 시각 칸은 모두 epoch 마이크로초 정수 문자열.

| `type` | 언제 | 더 싣는 칸 |
|---|---|---|
| `solve.applied` | 풀이 신호 반영(재시도는 없음) | `questionNo` · `questionId`(PK hex) · `successTime`(받은 ISO 문자열 그대로) · `clickedAt`(epoch µs) · `arrivedAt`(= `at`) · `progress`(반영 뒤) · `status`(반영 뒤) · `grace`(`1` = 08:00:00 ~ 08:00:03 유예로 반영) |
| `pass.used` | 패스 반영 | `questionNo` · `questionId`(넘긴 문제 PK) |
| `member.cheat_failed` | 부정 실패 반영 | `progress`(그대로) · `cheatFailedAt`(= `at`) |
| `group.decided` | 이 Lua 가 `HSETNX result` 에 성공했을 때만 | `result`(`SUCCEEDED` · `FAILED_IMMEDIATE`) · `decidedAt` · `memberCount` · `rankingFinal` · `ranking`(JSON) |

`ranking` JSON 한 줄은 `lib/ranking.lua` 의 출력이다(정본 [LLD deadline-result](deadline-result.md) §2.2): `{"id":"<uid>","u":"<login>","r":<rank>,"p":<-1~3>,"s":"<status>","l":"<µs>"|null,"n":<1~3>|null}` — 시각은 cjson 정밀도 때문에 문자열. persister 는 `started = (p ≥ 0)` · `cheat_failed = (s = CHEAT_FAILED)` 로 `group_result_members` 칸을 채운다. `group.decided` 칸 구성도 deadline-result 의 Lua `deadline` 이 내는 것과 같다.

`SPUBLISH ch:{<b>}:<gid>` 본문(JSON, 한 Lua 에 한 번 — 정본 [LLD realtime-board](realtime-board.md) §2.3): `{ type, streamId(마지막 XADD id), version, date, gid, uid, at(= t), hash(HGETALL 그룹 Hash) }`. 시각 · id 는 문자열. `type` 은 결과가 이번에 났으면 `group.decided`, 확정 그룹의 유예 반영이면 `solve.applied` + `hash.result` 있음 — 어느 SSE 이벤트로 보낼지는 realtime-board 가 정한다(HLD realtime-board §3: `member.progressed` · `group.result` 재전송).

### 2.3 PostgreSQL (`worker: persister` — LLD platform §3 과 같은 SQL)
| 이벤트 | SQL (한 트랜잭션) |
|---|---|
| `solve.applied` | ① `INSERT INTO solve_signals (game_date, user_id, question_no, question_id, success_time, click_time, arrived_at) VALUES (…) ON CONFLICT DO NOTHING` ② `UPDATE daily_progress SET progress = GREATEST(progress, :no), last_solved_at = CASE WHEN progress < :no THEN :arrivedAt ELSE last_solved_at END, status = CASE WHEN status = 'IN_PROGRESS' AND :no = 3 THEN 'SUCCEEDED' ELSE status END, updated_at = now() WHERE game_date = :d AND user_id = :u` ③ `UPDATE served_questions SET kind = 'SOLVED' WHERE user_id = :u AND question_id = :q` |
| `pass.used` | ① `INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) VALUES (…, 'PASSED', :at) ON CONFLICT (user_id, question_id) DO UPDATE SET kind = 'PASSED'` ② `UPDATE daily_progress SET pass_question_no = :no, passed_question_id = :q, updated_at = now() WHERE game_date = :d AND user_id = :u AND pass_question_no IS NULL` |
| `member.cheat_failed` | `UPDATE daily_progress SET status = 'CHEAT_FAILED', cheat_failed_at = :at, updated_at = now() WHERE game_date = :d AND user_id = :u AND status = 'IN_PROGRESS'` |
| `group.decided` | ① `INSERT INTO group_results (game_date, group_id, result, completed_at, member_count) VALUES (…) ON CONFLICT DO NOTHING` ② ①이 1행을 넣었을 때만 `INSERT INTO group_result_members (…) VALUES (ranking 각 줄) ON CONFLICT (game_date, user_id) DO NOTHING` ③ 커밋 뒤 결과 캐시 `result:{<gid>}` 갱신(LLD platform §3 「결과 캐시」 — 날짜 비교 Lua · `EX 90000`) |

- `questionId`(PK hex) → `questions.id` 는 persister 의 문제 캐시(기동 시 `SELECT id, pk_hash FROM questions`)로 바꾼다. 없으면 `SELECT id FROM questions WHERE pk_hash = :pk`.
- `success_time` 은 ISO 문자열을 파싱해 넣는다(기록만 — R-S5).

## 3. 처리 절차

### 3.0 공통 — SignalService (`core`)
1. 인증 주체에서 `uid` · `gid` 를 얻는다(auth-signup 이 access token 에 싣는다. 없으면 `UserRepository` 로 `group_id` 조회).
2. `date` = 앱 시계(KST)의 날짜, `b = gid mod 1024`. 시간 창은 `date` 와 `GameProperties` 로 epoch 마이크로초 세 값을 만든다: `openAt`(07:00:00) · `deadlineAt`(08:00:00) · `graceEndAt`(08:00:03). **판정은 Lua 가 Redis `TIME` 으로 한다** — 앱 시계는 날짜에만 쓴다(07:00 ~ 08:00:03 에 날짜가 바뀌지 않는다).
3. 포트 호출 → `SignalResult` 면 응답으로, `SignalRejection` 이면 §3.4 로.

### 3.1 Lua `solve`
**입력**
| 자리 | 값 |
|---|---|
| `KEYS[1]` | `g:{<b>}:<gid>:<date>` |
| `KEYS[2]` | `g:{<b>}:<gid>:<date>:members` |
| `KEYS[3]` | `ev:{<b>}` |
| `KEYS[4]` | `ch:{<b>}:<gid>` (Cluster 슬롯 검사를 위해 키로 넘긴다) |
| `ARGV[1]` ~ `ARGV[3]` | `uid` · `gid` · `date` |
| `ARGV[4]` ~ `ARGV[6]` | `openAt` · `deadlineAt` · `graceEndAt` (epoch µs) |
| `ARGV[7]` | `offsetUs` — `game.clock-offset` 을 µs 로(운영 0) |
| `ARGV[8]` ~ `ARGV[11]` | `questionNo` · `questionId` · `clickedAt`(epoch µs) · `successTime`(ISO 문자열, 기록만) |

**판정 순서** — `t = TIME + offsetUs`. 위에서부터 보고 처음 걸린 것으로 끝낸다(HLD §3 풀이 1~9).
| # | 조건 | 결과 |
|---|---|---|
| 0 | `KEYS[2]` 의 `#size` 없음(명단 미확정) | `NO_ROSTER` (§3.4) |
| 0′ | `HEXISTS KEYS[2] uid = 0` | `NOT_PARTICIPANT` |
| 0″ | `#size < 2`(그룹 Hash 가 없다) | `SOLO_GROUP` |
| 1 | `st = NOT_STARTED` | `MISSION_NOT_STARTED` |
| 2 | `questionNo ≤ p` | `REPLAY` — 아무것도 쓰지 않는다(`version` 그대로) |
| 3 | `st = CHEAT_FAILED` | `MISSION_ALREADY_FINISHED` |
| 4a | `t < openAt` | `GAME_NOT_OPEN` |
| 4b | `deadlineAt ≤ t < graceEndAt` 이고 `clickedAt ≥ deadlineAt` | `SUBMISSION_CLOSED` |
| 4c | `t ≥ graceEndAt` | `SUBMISSION_CLOSED` |
| 5 | `questionNo > p + 1` | `QUESTION_OUT_OF_ORDER` |
| 6 | `cur` 없음, 또는 `cur ≠ questionId` | `QUESTION_MISMATCH` |
| 7 | 반영 | `p = p + 1` · `ls = t` · `HDEL cur` · `st = IN_PROGRESS` 이고 `p = 3` 이면 `st = SUCCEEDED`(`FAILED` 는 그대로 — D17) · `HINCRBY version 1` |
| 8 | 그룹 판정 | `result` 없음 → `decide.lua`(§3.3a). `result` 있음 · `rankingFinal ≠ 1` → 랭킹만 다시 계산해 `ranking` 에 쓴다(deadline-result §3 유예) |
| 9 | 기록 · 전달 | `XADD solve.applied`(유예면 `grace=1`) → 8 에서 결과를 썼으면 `XADD group.decided` → `SPUBLISH` 1회 |
- 4 의 「08:00 확정 그룹」: 상태 `FAILED` · `result` 있음이어도 4b 를 통과하면 7 로 간다(D17). 다만 3번 지금 문제가 있는 `IN_PROGRESS` 그룹원은 그룹을 미루게 하므로(deadline-result §3) `FAILED` 그룹원이 3번을 푸는 경우는 생기지 않는다.

**출력** (Redis 배열 응답)
| 칸 | 값 |
|---|---|
| `[1]` | `OK` · `REPLAY` · 거부 코드(`NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` · `MISSION_NOT_STARTED` · `MISSION_ALREADY_FINISHED` · `GAME_NOT_OPEN` · `SUBMISSION_CLOSED` · `QUESTION_OUT_OF_ORDER` · `QUESTION_MISMATCH`) |
| `[2]` | 진행도 -1 ~ 3 (거부면 지금 값, `NO_ROSTER` 면 -1) |
| `[3]` | 그룹원 상태(본인 그대로 — `CHEAT_FAILED` 도 그대로) |
| `[4]` | 그룹 상태: `result = SUCCEEDED` → `SUCCEEDED` · `result` 의 그 밖 값 → `FAILED` · `pending = 1` 또는 `t ≥ deadlineAt` → `PENDING` · 그 밖 `IN_PROGRESS` |
| `[5]` | `version` |
| `[6]` | `t` (epoch µs) |

### 3.2 Lua `pass`
입력: `KEYS` 는 §3.1 과 같다. `ARGV[1]` ~ `ARGV[7]` 같음, `ARGV[8]` `questionNo` · `ARGV[9]` `questionId`.

| # | 조건 | 결과 |
|---|---|---|
| 0 · 0′ · 0″ | §3.1 과 같다 | `NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` |
| 1 | `st = NOT_STARTED` | `MISSION_NOT_STARTED` |
| 2a | `pn = questionNo` 이고 `pq = questionId` | `REPLAY` |
| 2b | `pn` 있음 | `PASS_ALREADY_USED` |
| 3 | `st ∈ {SUCCEEDED, CHEAT_FAILED}` | `MISSION_ALREADY_FINISHED` |
| 4 | `t < openAt` → `GAME_NOT_OPEN` · `t ≥ deadlineAt` 또는 `result` 있음 → `SUBMISSION_CLOSED` (유예 없음) | |
| 5 | `questionNo ≠ p + 1` → `QUESTION_OUT_OF_ORDER` · `cur` 없음 또는 `≠ questionId` → `QUESTION_MISMATCH` | |
| 6 | 반영 | `pn = questionNo` · `pq = questionId` · `HDEL cur` · `HINCRBY version 1` · `XADD pass.used` · `SPUBLISH` |
출력: §3.1 의 6칸 + `[7]` = `pn`.

### 3.3 Lua `fail`
입력: `KEYS` 는 §3.1 과 같다. `ARGV[1]` ~ `ARGV[7]` 만.

| # | 조건 | 결과 |
|---|---|---|
| 0 · 0′ · 0″ | §3.1 과 같다 | `NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` |
| 1 | `st = NOT_STARTED` | `MISSION_NOT_STARTED` (D14) |
| 2 | `st = SUCCEEDED` (D9) · `st = CHEAT_FAILED`(멱등) · `st = FAILED` · `t ≥ deadlineAt` (D17) · `result` 있음 | `REPLAY` — 바꾸지 않고 지금 상태 |
| 3 | 반영 | `st = CHEAT_FAILED` · `cf = t` · 진행도 그대로(R-F4) · `HINCRBY version 1` |
| 4 | 그룹 판정 | `decide.lua`(§3.3a) |
| 5 | 기록 · 전달 | `XADD member.cheat_failed` → 결과를 썼으면 `XADD group.decided` → `SPUBLISH` |
출력: §3.1 과 같은 6칸.

### 3.3a 그룹 판정 `decide.lua` (HLD §4.1)
1. 명단 Hash 의 모든 `<uid>`(`#` 로 시작하는 칸 제외)의 `st` 를 읽는다. 하나라도 `SUCCEEDED` · `CHEAT_FAILED` 가 아니면 끝(결과 없음).
2. 전원 `SUCCEEDED` → `SUCCEEDED`, 아니면 `FAILED_IMMEDIATE`.
3. `HSETNX result <값>` 이 `1` 일 때만: `decidedAt = t` · `ranking = ranking.lua(그룹 Hash, members)` · `rankingFinal = (t < deadlineAt) ? 1 : 0` · `HDEL pending` · 「결과를 썼다」 표시를 돌려준다. `0` 이면 아무것도 하지 않는다(같은 Lua 안이라 실제로는 1 에서 이미 걸러진다 — 방어선).

### 3.4 거부 코드 → 응답 (SignalService)
| Lua `[1]` | 처리 |
|---|---|
| `OK` · `REPLAY` | `200` — `progress = max(p, 0)` · `missionStatus = [3]` · `groupStatus = [4]` (재시도도 같은 본문) |
| `NO_ROSTER` | 명단이 아직 없다(07:00 전 · `worker: member` 늦음). `t < openAt` 이면 풀이 · 패스 → `GAME_NOT_OPEN`, 실패 → `MISSION_NOT_STARTED`. `t ≥ openAt` 이면 game-session 의 명단 만들기(Lua `roster` — 「없을 때만」이라 잡과 겹쳐도 한 번)를 부른 뒤 Lua 를 한 번 더 부른다. 그래도 `NO_ROSTER` 면 `MISSION_NOT_STARTED`(명단이 없으면 시작한 유저도 없다) |
| 그 밖 | 같은 이름의 `ErrorCode` 로 `BusinessException` |

### 3.5 Kotlin 쪽 해석 (`RedisProgressRepository`)
- `Instant` → epoch µs: `epochSecond * 1_000_000 + nano / 1_000`. 반대도 같다.
- `EVALSHA` 는 한 번만 부른다(재시도 없음 — 같은 신호의 재실행은 Lua 의 멱등이 처리하지만, 거부 · 반영이 섞인 응답을 만들지 않기 위해 `NOSCRIPT` 일 때만 1회 재시도).

### 3.6 `worker: persister` 처리기 (`SignalEventHandlers`)
1. `StreamPersister`(platform 일감)가 버킷 Stream 을 consumer group `persister` 로 `XREADGROUP COUNT 100 BLOCK 1000` 해 메시지를 `type` 별 처리기로 넘긴다(그 밖 `type` 은 다른 기능의 처리기).
2. 처리기는 §2.3 SQL 을 한 트랜잭션으로 실행하고, 커밋 뒤 `XACK`. 실패하면 ACK 하지 않는다 — 기동 때와 30초마다 `XAUTOCLAIM`(idle 30초)으로 다시 가져온다.
3. 같은 메시지를 두 번 처리해도 결과가 같다(PK · `ON CONFLICT` · `GREATEST` · `WHERE status = 'IN_PROGRESS'` · `pass_question_no IS NULL`).
4. 한 그룹의 메시지는 같은 버킷 Stream 에 Lua 실행 순서대로 있다 — 소비자는 버킷 하나를 한 번에 하나만 처리해 순서를 지킨다(`daily_progress` 의 `SUCCEEDED` 가 `group.decided` 보다 먼저 들어간다).

## 4. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| 같은 풀이 신호 재시도(반영 뒤 · 마감 뒤 · 결과 확정 뒤) | §3.1 의 2 가 시간 창보다 먼저 | `200` 같은 본문 |
| 같은 신호 동시 N건 | 같은 슬롯 Lua 직렬 실행 — 첫 건만 7 에 간다 | 모두 `200` 같은 본문 |
| 그룹 마지막 두 명이 동시에 완료 | Lua 직렬 — 두 번째 Lua 에서 전원 완료, `HSETNX` 1회 | 둘 다 `groupStatus = SUCCEEDED`(첫 응답은 `IN_PROGRESS` 일 수 있다 — 먼저 끝난 쪽) |
| 도착 08:00:02.9 · 클릭 07:59:59.9 | 4b 통과 | `200` 반영 |
| 도착 08:00:03.0 이상 | 4c | `409 SUBMISSION_CLOSED` |
| 도착 08:00:00 ~ 08:00:03 · 클릭 ≥ 08:00:00 | 4b | `409 SUBMISSION_CLOSED` |
| 08:00 확정 그룹(`FAILED`)의 유예 풀이 | 진행도 · `ls` 반영, 상태 · 결과 그대로, 랭킹 재계산 | `200` · `missionStatus FAILED` · `groupStatus FAILED` |
| 08:00 이후 · `worker: deadline` 처리 전 그룹 | 그룹 상태 `PENDING` | `200` 또는 `409`, 본문의 `groupStatus = PENDING` |
| 미션 성공 뒤 실패 신호 | §3.3 의 2 (D9) | `200` · `SUCCEEDED` |
| 08:00 이후 실패 신호 | §3.3 의 2 (D17) | `200` · 지금 상태 |
| 패스 08:00:00 이후 | 유예 없음 | `409 SUBMISSION_CLOSED` |
| `successTime` 이 미래 · 과거 | 검증하지 않는다(기록만 — R-S5) | 영향 없음 |
| `clickedAt` 오프셋 없음 · 형식 오류 | DTO 검증 | `400 INVALID_REQUEST` |
| Redis 장애(연결 실패) | 재시도하지 않고 5xx — 클라이언트가 같은 본문으로 재시도(멱등) | `500` (오류 코드 표 밖 — 공통 처리) |
| Lua 반영 뒤 응답 유실 | 클라이언트 재시도가 `REPLAY` | `200` 같은 본문 |
| persister 실패 · 재기동 | ACK 하지 않은 메시지 `XAUTOCLAIM` 재처리, SQL 멱등 | — |

## 5. 구현 순서
1. `ErrorCode` 추가 · `GameProperties`(`bucketCount` · `clockOffset` — 없으면) · `RedisKeys`.
2. `RedisScripts`(lib 이어 붙이기 · `SCRIPT LOAD` · `NOSCRIPT` 재적재) + `lib/common.lua`.
3. `solve.lua` + `decide.lua` + (없으면) `ranking.lua` — Testcontainers Redis 로 Lua 단위 테스트(ORACLE O1 ~ O17 · I1 ~ I4).
4. `pass.lua` · `fail.lua` 와 그 테스트(O18 ~ O29).
5. `ProgressRepository` 확장 · `RedisProgressRepository` · `SignalResult` · `SignalService`(§3.4 의 `NO_ROSTER` 처리는 game-session 의 명단 만들기를 부른다).
6. DTO 검증 · `SignalController` · `GlobalExceptionHandler` · API 테스트(O4 ~ O9) · `RouteSmokeTest` 정리.
7. `SignalEventHandlers` · `RdbSignalEventRepository`(O30 ~ O33) — `app:worker` 모듈 · `StreamPersister` 는 platform 일감이 먼저 만든다(LLD platform §5 3단계).
8. `docs/api/openapi.yaml` 반영 · 전체 ORACLE 명령 통과.
