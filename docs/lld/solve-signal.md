# LLD — solve-signal

> 판 v10 · 2026-11-11 (v10: T-DR-6 — `SignalEventHandlers` 가 `group.ranking_final` 도 맡고 두 그룹 결과 이벤트는 커밋 뒤 결과 캐시를 갱신(deadline-result LLD §3.4) · `group.decided` 의 `rankingFinal` 칸은 `0` · `1` 필수 · v9: 통합 — T-SS-4 판(v7a · v8a)과 T-SS-3 판(v7b · v8b)을 합침, 내용 충돌 없음 · v8a: T-SS-4 리뷰 반영 — 메시지 순서가 뒤바뀌어도(인스턴스 수 변경 · LLD platform §3.1 한계) 상태가 틀어지지 않게: `group.decided` `FAILED_DEADLINE` 의 FAILED 기록은 랭킹에서 `s = FAILED` 인 그룹원만 · `solve.applied` 3번의 `SUCCEEDED` 와 `member.cheat_failed` 는 `NOT_STARTED` 에서도(미션 시작 이벤트가 늦은 경우) · v7a: T-SS-4 구현 반영 — persister 처리기는 core 포트 `SignalEventRepository` · 기록 타입 `SignalRecords`(이벤트 칸 해석)로 app:worker → infra:rdb 의존을 끊는다 · 처리기는 4 `type` 을 맡는 빈 하나 · `group.decided` 의 `FAILED_DEADLINE` 이면 같은 트랜잭션에서 그룹원 `FAILED` 기록(LLD platform §3) · 문제 PK 는 이벤트마다 `SELECT id FROM questions WHERE pk_hash` (캐시 없음) · 결과 캐시는 T-DR-6 · `solve.applied` 의 진행도 갱신은 조건을 WHERE 로(재처리에 `updated_at` 도 그대로) · v8b: T-SS-3 리뷰 반영 — 시각 범위 2000 ~ 2099 년(µs 변환 넘침 · persister TIMESTAMPTZ 실패 방지) · `successTime` 은 파싱한 값을 `OffsetDateTime.toString()` 정규형으로 기록 · `questionNo` · `questionId` 는 JSON 타입 그대로 검사 · `NO_ROSTER` 명단 만들기 상한 08:00 · 버킷 계산 위치 정정 · v7b: T-SS-3 구현 반영 — 포트 반환은 `SignalReply`(= `SignalResult` | `SignalRejection`) · 명령은 `SignalTarget`(uid · gid · 날짜 · 시간 창 µs — 버킷은 `RedisKeys` 가 gid 로) · `SolveSignal.successTime` 은 받은 ISO 문자열 · 본문 검증은 DTO `toSignal()` 이 `BusinessException(INVALID_REQUEST)` 로(`GlobalExceptionHandler` 는 그대로) · `NO_ROSTER` 는 Lua `t` 와 앱 시각 둘 다 07:00 이후일 때만 명단을 만든다 · `QuestionRepository.markServed` · `MissionProgress.isFinished` 삭제 · v6: T-SS-2 리뷰 반영 — `pass` · `fail` 은 반영 직전 그룹원 상태가 `IN_PROGRESS` 가 아니면(알 수 없는 값) 스크립트 오류 · `fail` 은 `<uid>:cur` 를 그대로 둔다 · v5: T-SS-2 구현 반영 — `pass` 반환 `[7]` 은 패스 번호가 없으면 `0`(참가 거부도 7칸) · `pass` · `fail` 입력 형식 검사(`<uid>:pn` 포함) · `fail` 도 결과를 쓸 실행이면 쓰기 전에 `ranking.fromHash` 검사 · 테스트 도우미 `pass` · `fail` 호출과 I5 · I6 무작위 열의 `shown` 흉내 · v4: T-SS-1 리뷰 반영 — 랭킹을 쓸 실행은 **쓰기 전에** `ranking.fromHash` 로 칸 형식을 검사(부분 반영 방지) · `decide.allDone` · `<uid>:p` 형식 검사 · v3: T-SS-1 구현 반영 — `lib/common.lua` 도우미(`int` · `fmt` · `groupStatus` · `xadd` · `publish`) · `lib/decide.lua` 함수(`uids` · `run` · `ranking`) · `<uid>:ls` · `decidedAt` 쓰는 형식 · 입력 형식 검사 · 참가 거부의 빈 칸 · 테스트 도우미 `GroupHashFixture` · v2: 통합 — 시각 주입을 `game.clock-offset`(`GameProperties.clockOffset`) · Lua ARGV `offsetUs` 로 · Stream 공통 필드 `loginId` → `lid` 를 HLD platform §4.2 표로 · 채널 메시지를 realtime-board LLD §2.3 모양(`at` 더함 · `members` 뺌)으로 · `app:worker` 뼈대 · `StreamPersister` 소비 루프는 platform 일감) · 짝 문서: [HLD](../hld/solve-signal.md) · [LLD](../lld/solve-signal.md) · [SPEC](../spec/solve-signal.md) · [ORACLE](../oracle/solve-signal.md) · 근거: [PRD](../prd.md) v10 FR-4 · §5.4 ~ §5.7 · NFR-1 ~ NFR-3 · D1 · D2 · D9 · D14 · D17 · 바탕: [HLD platform](../hld/platform.md) §2.1 · §4.1 · [LLD platform](platform.md) §2.1 · §3

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
| `core/domain/src/main/kotlin/…/core/domain/progress/MissionProgress.kt` | 수정 | `solve` · `pass` · `failByCheat` · `isFinished` 도메인 메서드를 지운다 — 판정은 Lua 한 벌만 둔다(§3). `SolveOutcome` 을 `SignalResult` 로 바꾼다 |
| `core/domain/src/main/kotlin/…/core/domain/progress/SignalResult.kt` | 새로 | `sealed interface SignalReply`(공통 `arrivedAt` = Lua `t`) — `SignalResult`(Lua 반환을 옮긴 값: `outcome`(APPLIED · REPLAY) · `progress`(-1~3) · `missionStatus` · `groupStatus` · `version` · `arrivedAt` · `passedAtQuestionNo`(pass 만, 없으면 null)) · `SignalRejection(code, arrivedAt)`(`code` = Lua `[1]` 문자열 — `NO_ROSTER` 또는 같은 이름의 `ErrorCode`) |
| `core/domain/src/main/kotlin/…/core/domain/progress/SignalCommand.kt` | 새로 | Lua 에 넘길 값: 본문 `SolveSignal`(`successTime` 은 받은 ISO 문자열 그대로 · `clickedAt` Instant) · `PassSignal`, 공통 `SignalTarget`(uid · gid · 날짜 · `openAtUs` · `deadlineAtUs` · `graceEndAtUs` — 버킷은 `RedisKeys` 가 gid 로 정한다), `SolveCommand(target, signal)` · `PassCommand(target, signal)` · `FailCommand(target)`. `offsetUs` 는 저장소가 `GameProperties.clockOffsetUs` 로 붙인다 |
| `core/domain/src/main/kotlin/…/core/port/ProgressRepository.kt` | 수정 | `applySolve(SolveCommand)` · `applyPass(PassCommand)` · `applyCheatFail(FailCommand)` 를 더한다. 반환은 `SignalReply`(`SignalResult` 또는 `SignalRejection(code, arrivedAt)`) |
| `core/domain/src/main/kotlin/…/core/port/QuestionRepository.kt` · `infra/rdb/…/RdbQuestionRepository.kt` | 수정 | `markServed` 를 지운다 — 맞힌 · 패스한 문제 기록은 `worker: persister`(§2.3) |
| `core/domain/src/main/kotlin/…/core/service/SignalService.kt` | 수정 | 날짜 · 시간 창 계산(버킷은 `RedisKeys` 가 gid 로) → 포트 호출 → 거부 코드를 `BusinessException` 으로(§3.4) |
| `app/api/src/main/kotlin/…/app/api/controller/SignalController.kt` | 수정 | 세 엔드포인트. 인증 주체에서 uid · gid 를 꺼내 서비스 호출 |
| `app/api/src/main/kotlin/…/app/api/dto/SignalDtos.kt` | 수정 | 검증 규칙(SPEC §2) — `SolveRequest.toSignal()` · `PassRequest.toSignal()` 이 `questionNo` JSON 정수 1~3 · `questionId` JSON 문자열 `^[0-9a-f]{64}$`(두 칸은 `Any?` 로 받아 Jackson 형변환 없이 타입을 본다) · 시각 `ISO_OFFSET_DATE_TIME`(오프셋 필수 — 그래서 시각 칸은 문자열로 받는다) · 2000 ~ 2099 년을 보고, 어기면 `BusinessException(INVALID_REQUEST)`. 컨트롤러가 서비스보다 먼저 부른다(Redis 호출 0 — ORACLE O4) · `PassResponse` 에 `questionNo`(요청 값) · `progress` 추가 · 상태는 enum 문자열 |
| `app/api/src/main/kotlin/…/app/api/web/GlobalExceptionHandler.kt` | (그대로) | 디코딩 실패(본문 없음 · JSON 아님 · 필드 빠짐 · 타입 위반)는 기존 `ServerWebInputException` 처리가, 규칙 위반은 `BusinessException` 처리가 `400 INVALID_REQUEST` 로 낸다 — 고칠 것이 없다 |
| `infra/redis/src/main/resources/lua/lib/common.lua` | 새로(T-PF-2) · 수정 | 시각 `common.now(offsetUs)`(= `TIME` + 오프셋, T-PF-2) 에 더한다: `common.int(v, name)`(ARGV 십진 정수 검사 → 숫자, 아니면 스크립트 오류) · `common.fmt(n)`(숫자 → 십진 정수 문자열 `string.format('%.0f', n)` — µs 시각 · `version` 을 쓸 때) · `common.groupStatus(key, t, deadlineAt)`(§3.1 출력 `[4]`) · `common.xadd(key, fields)`(평평한 `{이름, 값, …}` 로 `XADD key *`, id 반환) · `common.publish(channel, groupKey, type, streamId, date, gid, uid, t)`(`HGETALL` 을 실어 realtime-board LLD §2.3 본문으로 `SPUBLISH`) |
| `infra/redis/src/main/resources/lua/lib/decide.lua` | 새로 | 그룹 판정(HLD §4.1) — `solve` · `fail` 공용. `decide.uids(membersKey)`(명단의 `#` 아닌 칸) · `decide.allDone(groupKey, uids, self, selfSt)`(전원 완료인가 — `self` 는 아직 쓰지 않은 반영 뒤 상태 `selfSt` 로 본다) · `decide.run(groupKey, uids, t, deadlineAt)`(§3.3a — 결과를 이번에 썼으면 그 값, 아니면 `nil`) · `decide.ranking(groupKey, uids)`(= `ranking.encode(ranking.rank(ranking.fromHash(groupKey, uids)))`) |
| `infra/redis/src/main/resources/lua/lib/ranking.lua` | 새로(**deadline-result 소유**) | 랭킹 비교 함수(deadline-result HLD §4). 이 기능은 부르기만 한다 — 먼저 구현하는 쪽이 만들고 다른 쪽은 그대로 쓴다 |
| `infra/redis/src/main/resources/lua/solve.lua` · `pass.lua` · `fail.lua` | 새로 | §3.1 ~ §3.3 |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisKeys.kt` | 새로 | 키 이름 만들기(§2.1 — platform §4.1 과 같은 이름) |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisScripts.kt` | 새로 | `lib/*.lua` + 스크립트 본문을 이어 붙여 `SCRIPT LOAD` → `EVALSHA`, `NOSCRIPT` 면 다시 적재 후 1회 재시도 |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisProgressRepository.kt` | 수정 | `applySolve` · `applyPass` · `applyCheatFail` — `RedisScripts` 호출 · 반환 배열 해석(§3.5) |
| `settings.gradle.kts` · `app/worker/build.gradle.kts` · `app/worker/src/main/kotlin/…/app/worker/WorkerApplication.kt` | (platform 일감이 만든다) | 세 번째 앱 모듈(HLD platform §5 · LLD platform §5 3단계). 다른 worker(`deadline` · `member` · `prepare-questions`)도 여기 산다 |
| `app/worker/src/main/kotlin/…/app/worker/persister/StreamPersister.kt` | (platform 일감이 만든다 — LLD platform §5 3단계) | 버킷 Stream `XREADGROUP` 소비 루프 · 종류별 처리기 호출 · 커밋 뒤 `XACK`. 이 기능은 처리기를 등록만 한다 |
| `core/domain/src/main/kotlin/…/core/domain/progress/SignalRecords.kt` | 새로 | Stream 칸(§2.2) → 기록 값 `SolveRecord` · `PassRecord` · `CheatFailRecord` · `GroupDecidedRecord`(+ `ResultMemberRecord`). `SignalRecords.parse(fields)` 는 칸이 없거나 형식이 틀리면 `IllegalArgumentException` |
| `core/domain/src/main/kotlin/…/core/port/SignalEventRepository.kt` | 새로 | `recordSolve` · `recordPass` · `recordCheatFail` · `recordGroupDecided`(→ `group_results` 를 이번에 넣었으면 true). 메서드 하나 = 한 트랜잭션(§2.3) — app:worker 는 infra 에 기대지 않는다(아키텍처 규칙) |
| `app/worker/src/main/kotlin/…/app/worker/persister/SignalEventHandlers.kt` | 새로 | 이 기능의 처리기(§3.6) — 4 `type` 을 맡는 `PersisterEventHandler` 빈 하나. 해석 실패는 `MalformedEventException` |
| `infra/rdb/src/main/kotlin/…/infra/rdb/RdbSignalEventRepository.kt` | 새로 | §2.3 SQL — R2DBC `DatabaseClient` · `TransactionalOperator`(`RdbInfraAutoConfiguration` 에 등록) |
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
| `<uid>:ls` | 마지막 풀이 도착 시각(epoch 마이크로초 십진 정수 — `common.fmt(t)` = `string.format('%.0f', t)` 로 쓴다. `tostring` 은 지수 표기를 내고 `ranking.fromHash` 가 거절한다) | 쓰기 |
| `<uid>:pn` | 패스 번호 1~3(없으면 칸 없음) | 읽기 · 쓰기 |
| `<uid>:pq` | 패스로 넘긴 문제 PK(hex 64) | 읽기 · 쓰기 |
| `<uid>:cur` | 지금 문제 PK — 언제나 번호 `<uid>:p + 1` 의 문제(game-session 의 `shown` 이 채우고 여기서 비운다) | 읽기 · 지우기 |
| `<uid>:cf` | 부정 실패 시각(epoch 마이크로초) | 쓰기 |
| `version` | 정수, 그룹이 바뀔 때마다 +1 — SSE `version` | `HINCRBY 1` |
| `pending` | `1` = 08:00 판정 미룸(deadline-result) | 읽기 · 판정 때 지우기 |
| `result` | `SUCCEEDED` · `FAILED_IMMEDIATE` · `FAILED_DEADLINE` | `HSETNX` |
| `decidedAt` | 결과 확정 시각(epoch 마이크로초 — `ls` 와 같은 형식) | 쓰기 |
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
| `solve.applied` | ① `INSERT INTO solve_signals (game_date, user_id, question_no, question_id, success_time, click_time, arrived_at) VALUES (…) ON CONFLICT DO NOTHING` ② `UPDATE daily_progress SET progress = :no, last_solved_at = :arrivedAt, status = CASE WHEN status IN ('NOT_STARTED','IN_PROGRESS') AND :no = 3 THEN 'SUCCEEDED' ELSE status END, updated_at = now() WHERE game_date = :d AND user_id = :u AND progress < :no`(= `GREATEST` 와 같은 뜻 — 조건을 WHERE 에 두어 재처리 때 `updated_at` 도 바뀌지 않는다 · ORACLE O30 「변화 0」) ③ `UPDATE served_questions SET kind = 'SOLVED' WHERE user_id = :u AND question_id = :q AND kind <> 'SOLVED'` |
| `pass.used` | ① `INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) VALUES (…, 'PASSED', :at) ON CONFLICT (user_id, question_id) DO UPDATE SET kind = 'PASSED' WHERE served_questions.kind <> 'PASSED'` ② `UPDATE daily_progress SET pass_question_no = :no, passed_question_id = :q, updated_at = now() WHERE game_date = :d AND user_id = :u AND pass_question_no IS NULL` |
| `member.cheat_failed` | `UPDATE daily_progress SET status = 'CHEAT_FAILED', cheat_failed_at = :at, updated_at = now() WHERE game_date = :d AND user_id = :u AND status IN ('NOT_STARTED','IN_PROGRESS')` (`NOT_STARTED` = 미션 시작 이벤트가 늦게 온 경우 — Lua 는 `IN_PROGRESS` 그룹원에만 이 이벤트를 낸다) |
| `group.decided` | ① `INSERT INTO group_results (game_date, group_id, result, completed_at, member_count) VALUES (…) ON CONFLICT DO NOTHING` ② ①이 1행을 넣었을 때만 `INSERT INTO group_result_members (…) VALUES (ranking 각 줄) ON CONFLICT (game_date, user_id) DO NOTHING` ③ `result = FAILED_DEADLINE`(deadline-result 의 Lua `deadline` 이 낸 것)이면 `UPDATE daily_progress SET status = 'FAILED', updated_at = now() WHERE game_date = :d AND group_id = :g AND user_id = ANY(:failed) AND status IN ('NOT_STARTED','IN_PROGRESS')` — `:failed` = 랭킹에서 `s = FAILED` 인 그룹원(LLD platform §3 「마감 실패 그룹원」 — ①의 삽입 여부와 무관하게, 멱등). 랭킹으로 한정하는 까닭: 이 그룹원의 늦게 온 `solve.applied` 3번이 아직 반영되지 않았는데 PG 상태만 보고 `FAILED` 로 바꾸면 그 뒤 `SUCCEEDED` 로 돌아가지 못한다 ④ 커밋 뒤 결과 캐시 `result:{<gid>}` 갱신(LLD platform §3 「결과 캐시」 — 날짜 비교 Lua · `EX 90000` · **T-DR-6** 이 이 처리기에 더한다) |

- `questionId`(PK hex) → `questions.id` 는 같은 트랜잭션에서 `SELECT id FROM questions WHERE pk_hash = :pk`(UNIQUE 인덱스 한 번)로 바꾼다 — 기동 때 문제 캐시는 두지 않는다(문제 수만큼 메모리 · 시드 뒤 추가된 문제 반영 문제를 피함). 없으면 예외 → 재처리(버킷이 멈춰 `persister.stuck` 경보 — 출제된 문제가 표에 없는 손상이라 기록을 버리지 않는다).
- `group.ranking_final`(deadline-result 가 내는 08:00:03 랭킹 확정)도 이 처리기가 맡는다 — SQL 은 LLD platform §3 · deadline-result LLD §3.4 2. `group.decided` · `group.ranking_final` 은 커밋 뒤 결과 캐시를 `ResultCache.putIfNotOlder` 로 갱신한다.
- `group.decided` 의 `ranking` 줄 수가 `memberCount` 와 다르거나 `rankingFinal` 이 `0` · `1` 이 아니거나 칸이 틀리면 해석 실패(`MalformedEventException`) — 시각 칸(`at` · `arrivedAt` · `clickedAt` · `decidedAt` · `cheatFailedAt` · 랭킹 `l`)은 µs 십진 정수, `date` 는 `YYYY-MM-DD`, `gid` · `uid` 는 정수여야 한다.
- `success_time` 은 ISO 문자열을 파싱해 넣는다(기록만 — R-S5).

## 3. 처리 절차

### 3.0 공통 — SignalService (`core`)
1. 인증 주체에서 `uid` · `gid` 를 얻는다(auth-signup 이 access token 에 싣는다. 없으면 `UserRepository` 로 `group_id` 조회).
2. `date` = 앱 시계(KST)의 날짜(버킷 `b = gid mod 1024` 는 키를 만드는 `RedisKeys` 가 정한다). 시간 창은 `date` 와 `GameProperties` 로 epoch 마이크로초 세 값을 만든다: `openAt`(07:00:00) · `deadlineAt`(08:00:00) · `graceEndAt`(08:00:03). **판정은 Lua 가 Redis `TIME` 으로 한다** — 앱 시계는 날짜에만 쓴다(07:00 ~ 08:00:03 에 날짜가 바뀌지 않는다).
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

**입력 형식** — `ARGV[4]` ~ `ARGV[7]` · `ARGV[10]` 은 십진 정수(`common.int`), `ARGV[8]` 은 `1` · `2` · `3` 이어야 한다. 어긋나면 스크립트 오류(앱의 DTO 검증이 먼저 막는다 — 여기는 방어선). 명단에 있는 그룹원인데 그룹 Hash 에 `<uid>:st` · `<uid>:lid` 가 없거나 `<uid>:p` 가 -1 ~ 3 정수 꼴이 아니어도 스크립트 오류(조용히 넘기지 않는다).

**쓰기 전에 검사한다** — Redis Lua 는 오류가 나도 앞서 쓴 것을 되돌리지 않는다. 그래서 7(반영) 전에 이 실행이 랭킹을 쓸지(8 — 결과 없음이고 `decide.allDone` 이 참, 또는 결과 있음 · `rankingFinal ≠ 1`)를 보고, 쓴다면 `ranking.fromHash(KEYS[1], uids)` 를 한 번 불러 그룹원 칸 형식을 먼저 검사한다. 칸이 손상됐으면 아무것도 쓰지 않은 채 스크립트 오류로 끝난다(반영됐는데 Stream · 채널이 빠지는 부분 반영을 막는다).

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
| `[2]` | 진행도 -1 ~ 3 (거부면 지금 값, `NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` 이면 -1) |
| `[3]` | 그룹원 상태(본인 그대로 — `CHEAT_FAILED` 도 그대로). `NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` 이면 `''`(그룹 Hash 를 읽지 않는다) |
| `[4]` | 그룹 상태: `result = SUCCEEDED` → `SUCCEEDED` · `result` 의 그 밖 값 → `FAILED` · `pending = 1` 또는 `t ≥ deadlineAt` → `PENDING` · 그 밖 `IN_PROGRESS`(`common.groupStatus`). `NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` 이면 `''` |
| `[5]` | `version`(정수 — 위 세 거부면 0) |
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
출력: §3.1 의 6칸 + `[7]` = `pn`(정수 — 패스를 쓰지 않았으면 `0`. `NO_ROSTER` · `NOT_PARTICIPANT` · `SOLO_GROUP` 도 7칸 `{코드, -1, '', '', 0, t, 0}`). 응답 `questionNo` 는 요청 값이다.

**입력 형식** — `ARGV[4]` ~ `ARGV[7]` 은 십진 정수(`common.int` · `common.now`), `ARGV[8]` 은 `1` · `2` · `3`. 그룹원 칸 검사는 §3.1 과 같고, `<uid>:pn` 이 있으면 `1` ~ `3` 이어야 한다. 1 ~ 5 를 모두 지나 6(반영) 직전인데 그룹원 상태가 `IN_PROGRESS` 가 아니면(표에 없는 값 — 손상) 조용히 덮지 않고 스크립트 오류다. 패스는 그룹원 상태를 바꾸지 않으므로 그룹 판정이 없고, 결과가 난 그룹은 4 에서 거부되므로 랭킹을 다시 쓰지 않는다.

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

**입력 형식** — `ARGV[4]` ~ `ARGV[7]` 은 십진 정수, 그룹원 칸 검사는 §3.1 과 같다. **쓰기 전 검사**도 §3.1 과 같다 — 3(반영) 전에 `decide.allDone(KEYS[1], uids, uid, 'CHEAT_FAILED')` 가 참이면(이번 실행이 결과를 쓴다) `ranking.fromHash` 로 그룹원 칸을 먼저 검사한다. 2 에서 결과 있음을 걸렀으므로 확정 뒤 재계산은 없다. 2 를 지났는데 그룹원 상태가 `IN_PROGRESS` 가 아니면(표에 없는 값 — 손상) 반영하지 않고 스크립트 오류다. `<uid>:cur` 는 지우지 않는다 — 부정 실패한 그룹원은 더 풀지 않고, `worker: deadline` 은 `IN_PROGRESS` 그룹원의 지금 문제만 본다.

### 3.3a 그룹 판정 `decide.lua` (HLD §4.1)
1. 명단 Hash 의 모든 `<uid>`(`#` 로 시작하는 칸 제외)의 `st` 를 읽는다. 하나라도 `SUCCEEDED` · `CHEAT_FAILED` 가 아니면 끝(결과 없음).
2. 전원 `SUCCEEDED` → `SUCCEEDED`, 아니면 `FAILED_IMMEDIATE`.
3. `HSETNX result <값>` 이 `1` 일 때만(이 함수는 `version` 을 올리지 않는다 — 부른 Lua 가 반영에서 한 번 올렸다. 한 실행에 `version` +1 은 한 번): `decidedAt = t` · `ranking = ranking.lua(그룹 Hash, members)` · `rankingFinal = (t < deadlineAt) ? 1 : 0` · `HDEL pending` · 「결과를 썼다」 표시를 돌려준다. `0` 이면 아무것도 하지 않는다(같은 Lua 안이라 실제로는 1 에서 이미 걸러진다 — 방어선).

### 3.4 거부 코드 → 응답 (SignalService)
| Lua `[1]` | 처리 |
|---|---|
| `OK` · `REPLAY` | `200` — `progress = max(p, 0)` · `missionStatus = [3]` · `groupStatus = [4]` (재시도도 같은 본문) |
| `NO_ROSTER` | 명단이 아직 없다(07:00 전 · `worker: member` 늦음). `t < openAt` **또는 앱 게임 시각(`GameClock.now()`) < openAt** 이면 풀이 · 패스 → `GAME_NOT_OPEN`, 실패 → `MISSION_NOT_STARTED`(Redis 시각과 앱 시계가 07:00 경계에서 어긋나도 `RosterService` 의 「07:00 전에는 만들지 않는다」를 어기지 않는다). `t ≥ deadlineAt` 또는 앱 게임 시각 ≥ deadlineAt 이면 명단을 만들지 않고 `MISSION_NOT_STARTED`(마감이 지난 날짜에 명단 · 그룹 Hash · -1 행을 만들면 `worker: deadline` 이 이미 지나 결과 없는 그룹이 남는다). 둘 다 `openAt` ~ `deadlineAt` 이면 game-session 의 명단 만들기(Lua `roster` — 「없을 때만」이라 잡과 겹쳐도 한 번)를 부른 뒤 Lua 를 한 번 더 부른다. 그래도 `NO_ROSTER` 면 `MISSION_NOT_STARTED`(명단이 없으면 시작한 유저도 없다) |
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
| `successTime` · `clickedAt` 이 미래 · 과거 | 2000 ~ 2099 년 안이면 검증하지 않는다(기록만 — R-S5). 밖이면 DTO 가 거부 — µs 변환 넘침(500)과 persister 의 `TIMESTAMPTZ` 범위 실패(ACK 안 됨 → 버킷 정체)를 막는다. `successTime` 은 파싱한 값의 정규형(`OffsetDateTime.toString()`)으로 넘긴다 | 범위 밖 `400 INVALID_REQUEST` |
| `clickedAt` 오프셋 없음 · 형식 오류 | DTO 검증 | `400 INVALID_REQUEST` |
| Redis 장애(연결 실패) | 재시도하지 않고 5xx — 클라이언트가 같은 본문으로 재시도(멱등) | `500` (오류 코드 표 밖 — 공통 처리) |
| Lua 반영 뒤 응답 유실 | 클라이언트 재시도가 `REPLAY` | `200` 같은 본문 |
| persister 실패 · 재기동 | ACK 하지 않은 메시지 `XAUTOCLAIM` 재처리, SQL 멱등 | — |

## 5. 구현 순서
1. `ErrorCode` 추가 · `GameProperties`(`bucketCount` · `clockOffset` — 없으면) · `RedisKeys`.
2. `RedisScripts`(lib 이어 붙이기 · `SCRIPT LOAD` · `NOSCRIPT` 재적재) + `lib/common.lua`.
3. `solve.lua` + `decide.lua` + (없으면) `ranking.lua` — Testcontainers Redis 로 Lua 단위 테스트(ORACLE O1 ~ O17 · I1 ~ I4). 테스트 도우미는 `infra/redis/src/test/kotlin/…/infra/redis/signal/` 의 `GroupHashFixture`(§2.1 칸 직접 쓰기) · `SignalScriptSupport`(오프셋 계산 · Stream · 채널 구독 `SSUBSCRIBE`), `decide` 단독 검사는 테스트 래퍼 `src/test/resources/lua/decide_probe.lua`.
4. `pass.lua` · `fail.lua` 와 그 테스트(O18 ~ O29).
5. `ProgressRepository` 확장 · `RedisProgressRepository` · `SignalResult` · `SignalService`(§3.4 의 `NO_ROSTER` 처리는 game-session 의 명단 만들기를 부른다).
6. DTO 검증 · `SignalController` · `GlobalExceptionHandler` · API 테스트(O4 ~ O9) · `RouteSmokeTest` 정리.
7. `SignalEventHandlers` · `RdbSignalEventRepository`(O30 ~ O33) — `app:worker` 모듈 · `StreamPersister` 는 platform 일감이 먼저 만든다(LLD platform §5 3단계).
8. `docs/api/openapi.yaml` 반영 · 전체 ORACLE 명령 통과.
