# LLD — deadline-result

> 판 v10 · 2026-11-11 (v10: T-DR-4 구현 반영 — `scanDayGroups` 는 `Flow<GroupId>` · `DeadlineService` 는 빈 주석 없는 클래스로 worker `DeadlineConfiguration` 이 만들고 `DeadlineReport` 를 돌려준다(로그는 worker — core 서비스는 로거에 기대지 않는다) · `DeadlineProperties` 에 `enabled` · `early-retry-delay` · `early-retry-max` · `retry-backoff` · 버킷 `SSCAN` 오류는 버킷째 재시도 · 재시작 때 다시 도는 날짜 규칙 · 2차는 `max(now, 08:00:03)` · 테스트 파일 이름 · v9: T-DR-3 리뷰 반영 — Lua `deadline` 은 오류가 날 수 있는 검사(명단 없음 · 그룹원 칸 형식 · `HSETNX`)를 쓰기 전에 한다 · 명단 Hash 가 비면 오류 · v8: T-DR-3 구현 반영 — `runDeadline` 반환 `DeadlineOutcome(code, version)` · `DeadlinePhase` · `DeadlineCode`(`core/domain/result/Deadline.kt`) · `GameProperties.deadlineAt` · `graceEndAt` · Lua `deadline` 의 ARGV 검사 · 랭킹 확정(6.2)도 `pending` 을 지운다 · 테스트 `DeadlineLuaTest` · v7: T-RB-1 구현 반영 — 그룹원 상태 enum 은 `MemberStatus`(`MissionStatus` 삭제) · `Board.rank` 삭제(순위는 realtime-board `BoardView`) · v6: T-DR-2 리뷰 반영 — `fromHash` 칸 형식 검사 · `encode` 입력 조건 · 랭킹 JSON 은 파싱해 비교 · `deadline` 은 상태를 `FAILED` 로 바꾼 뒤 랭킹을 읽는다 · v5: T-DR-2 구현 반영 — `lib/ranking.lua` 의 함수 목록(`cmp` · `bytecmp` · `rank` · `fromHash` · `encode`) · 테스트 래퍼 `rank_only.lua` 입력 모양 · 무작위 그룹 생성기 `RankingRandomGroups`(testFixtures) · v4: T-DR-1 구현 반영 — `RankingKey` 불변식(진행도 1~3 이면 `lastSolvedAtUs` 필수) · `rank` 반환 `List<RankedKey>` · 픽스처 모양 · v3: 이름 통일 — Lua 로더 `RedisScripts` · DTO `MemberEntryResponse` · v2: 통합 — 채널 메시지를 realtime-board LLD §2.3 모양(`type` `group.decided` · `group.pending` · `group.ranking_final`)으로 · Stream 공통 필드(`uid` · `lid` · `at`)를 HLD platform §4.2 로 · 시각 주입 설정 이름 `game.clock-offset` · `app:worker` 뼈대 · persister 소비 루프는 platform 일감 · 인덱스 2개는 LLD platform v3 DDL 에 반영됨) · 짝 문서: [HLD](../hld/deadline-result.md) · [LLD](../lld/deadline-result.md) · [SPEC](../spec/deadline-result.md) · [ORACLE](../oracle/deadline-result.md) · 근거: [PRD](../prd.md) v10 FR-6 · FR-7 · §5.6 · §5.9 · §6 · NFR-1 · NFR-2 · NFR-4 · D1 · D3 · D4 · D8 · D17 · 바탕: [HLD platform](../hld/platform.md) §2.1 · §4.1 · [LLD platform](../lld/platform.md) §2.1 · §3

**세 줄 요약**
- 바뀌는 것: 새 앱 모듈 `app:worker` 의 `worker: deadline`(08:00:00 1차 · 08:00:03 2차, 버킷 1,024개 병렬) · Lua `deadline` · 공용 Lua 랭킹 함수 `lib/ranking.lua` 와 같은 규칙의 Kotlin `Ranking` · 결과 조회 `GET /api/v1/games/results/latest` · 결과 캐시 쓰기 Lua `result_cache_put`.
- 데이터 모델: 새 테이블은 없다. 그룹 Hash 의 `pending` · `result` · `decidedAt` · `ranking` · `rankingFinal` · `version` 과 그룹원 상태(→ `FAILED`)를 쓰고, 결과 캐시 `result:{<gid>}`(TTL 90000초)를 쓴다. PostgreSQL 은 읽기만 한다 — 지난 결과 조회용 인덱스 2개(§2.3)는 LLD platform v3 DDL 에 있다.
- 구현 순서: Kotlin 랭킹 → Lua 랭킹 + 일치 테스트 → Lua `deadline` → `worker: deadline` → 결과 조회(Hash · 캐시 · PostgreSQL) → 결과 캐시 쓰기(persister 연결).

## 1. 모듈 · 파일
경로의 기준은 `backend/`. 패키지 접두 `com.delightroom.mission`(아래 `…`).

| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `core/domain/src/main/kotlin/…/core/domain/ranking/RankingKey.kt` | 새로 | 랭킹 입력 한 줄: `loginId` · `cheatFailed` · `progress`(-1~3) · `lastSolvedAtUs: Long?` · `passNo: Int?` · `late: Boolean`. 만들 때 `progress` 가 -1~3 이고, 1~3 이면 `lastSolvedAtUs` 가 있어야 한다(없으면 `IllegalArgumentException` — Lua `ranking.fromHash` 도 같은 입력을 스크립트 오류로 거절한다). 출력 한 줄 `RankedKey(key, rank)` 도 이 파일에 둔다 |
| `core/domain/src/main/kotlin/…/core/domain/ranking/Ranking.kt` | 새로 | §4 비교 함수 `compare(a, b)` · 경쟁 순위 `rank(keys): List<RankedKey>`(표시 순서). Lua `lib/ranking.lua` 와 같은 규칙(HLD §4) |
| `core/domain/src/main/kotlin/…/core/domain/board/Board.kt` | 수정 | `GroupStatus` 를 `IN_PROGRESS · PENDING · SUCCEEDED · FAILED` 로(`NO_RESULT` 삭제), 그룹원 상태는 `MemberStatus`(5개 — `MissionStatus` 는 지웠다)를 쓴다. `Board.rank` 는 지웠다 — 현황판 순위는 realtime-board 의 `BoardView` 가 `Ranking.rank` 로 붙인다(realtime-board 와 함께 쓰는 파일) |
| `core/domain/src/main/kotlin/…/core/domain/result/LatestResult.kt` | 새로 | 결과 조회의 도메인 값 `LatestResult(date, groupStatus, decidedAt?, rankingFinal, ranking: List<RankedMember>)` · `RankedMember(loginId, rank, progress(-1~3), status, lastSolvedAt?, passNo?)` |
| `core/domain/src/main/kotlin/…/core/domain/result/Deadline.kt` | 새로 | 마감 처리의 값: `DeadlinePhase`(`FIRST` 1 · `SECOND` 2 — Lua ARGV `phase`) · `DeadlineCode`(§4.1 반환 코드 7개) · `DeadlineOutcome(code, version)` |
| `core/domain/src/main/kotlin/…/core/port/GroupDayRepository.kt` | 새로 | 그룹 Hash 쪽 포트: `findResultState(gid, date)` · `runDeadline(gid, date, phase: DeadlinePhase): DeadlineOutcome` · `scanDayGroups(bucket, date): Flow<GroupId>`(`SSCAN … COUNT 1000` 을 끝까지 — 값이 정수가 아니면 오류) — `runDeadline` 은 T-DR-3, `scanDayGroups` 는 T-DR-4, `findResultState` 는 T-DR-5 가 더한다 |
| `core/domain/src/main/kotlin/…/core/port/ResultCache.kt` | 새로 | 결과 캐시 포트: `get(gid)` · `putIfAbsent(gid, result)`(조회) · `putIfNotOlder(gid, result)`(persister) |
| `core/domain/src/main/kotlin/…/core/port/GameResultRepository.kt` | 수정 | `findLatest(gid): LatestResult?` 추가. `recordOnce` 는 persister(platform) 몫으로 남긴다 |
| `core/domain/src/main/kotlin/…/core/service/BoardService.kt` | 수정 | `latestResult(principal)` — §3.3 순서. `ResultView` 에 `myRank` · `nextGameStartAt` |
| `core/domain/src/main/kotlin/…/core/service/DeadlineService.kt` | 새로 | 한 단계(1차 · 2차)를 버킷 병렬로 돈다(§3.2) — `runPhase(date, phase): DeadlineReport`(코드별 수 · 맡은 버킷 수 · 실패 gid · 실패 버킷 · 걸린 시간). worker 만 쓰므로 `@Service` 를 달지 않고 worker `DeadlineConfiguration` 이 `DeadlineService.Options`(= `DeadlineProperties`)로 만든다. 로그는 부른 쪽(worker)이 보고서로 남긴다 — core 서비스는 core · 코루틴에만 기댄다(아키텍처 규칙) |
| `core/domain/src/main/kotlin/…/core/config/GameProperties.kt` | 수정 | `deadlineAt(date)`(08:00:00 KST) · `graceEndAt(date) = deadlineAt + submitGrace`(08:00:03) — Lua `deadline` 의 `deadlineUs` · `graceEndUs` · `resultCacheTtl = 25h`(T-DR-5) |
| `infra/redis/src/main/resources/lua/lib/ranking.lua` | 새로 | Lua 랭킹 모듈 `ranking`(§4.2 — `cmp` · `bytecmp` · `rank` · `fromHash` · `encode`). 스크립트를 올릴 때 앞에 붙인다 — `deadline` 과 `solve` · `fail`(solve-signal)이 `ranking.encode(ranking.rank(ranking.fromHash(KEYS[1], uids)))` 한 줄로 §2.2 랭킹 JSON 을 만든다 |
| `infra/redis/src/main/resources/lua/deadline.lua` | 새로 | Lua `deadline`(§4.1) |
| `infra/redis/src/main/resources/lua/result_cache_put.lua` | 새로 | 결과 캐시 날짜 비교 쓰기(§4.3) |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisScripts.kt` | 새로(없으면) | `lua/lib/*.lua` + 본문을 이어 `SCRIPT LOAD` · `EVALSHA`, `NOSCRIPT` 면 다시 올리고 한 번 재시도. 다른 기능 Lua 와 함께 쓰는 로더 |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisGroupDayRepository.kt` | 새로 | `GroupDayRepository` 구현 — `HMGET`(결과 상태) · `EVALSHA deadline`(ARGV 는 §4.1 — 경계는 `GameProperties.deadlineAt` · `graceEndAt` 을 µs 로, `offsetUs` = `clockOffsetUs`) · `SSCAN day:{<b>}:<date> COUNT 1000`(`ReactiveStringRedisTemplate`). `RedisInfraAutoConfiguration` 이 빈으로 올린다 |
| `infra/redis/src/main/kotlin/…/infra/redis/RedisResultCache.kt` | 새로 | `ResultCache` 구현 — `GET` · `SET NX EX 90000` · `EVALSHA result_cache_put` |
| `infra/rdb/src/main/kotlin/…/infra/rdb/RdbGameResultRepository.kt` | 수정 | `findLatest` — §2.3 쿼리 |
| `app/api/src/main/kotlin/…/app/api/controller/BoardController.kt` | 수정 | `latestResult` 가 `BoardService.latestResult` 를 부른다(501 제거) |
| `app/api/src/main/kotlin/…/app/api/dto/BoardDtos.kt` | 수정 | `ResultResponse` 에 `rankingFinal` 추가, `MemberEntryResponse`(= MemberEntry)에서 `cheatFailed` 삭제 · `started` 필수(SPEC §2). realtime-board 와 함께 쓰는 파일 |
| `settings.gradle.kts` · `app/worker/build.gradle.kts` · `…/app/worker/WorkerApplication.kt` | (platform 일감이 만든다) | 세 번째 앱 모듈(HLD platform §5 · LLD platform §5 3단계). 웹 서버 없이 스케줄러만. 이 기능은 `deadline` 패키지만 더한다 |
| `app/worker/src/main/kotlin/…/app/worker/deadline/DeadlineScheduler.kt` | 새로 | 매일 08:00:00 · 08:00:03(KST, 주입한 `Clock` + `game.clock-offset`)에 `DeadlineService.runPhase` 를 띄운다(`SmartLifecycle` · 코루틴 하나). 기동 때 08:00 이 지났으면 그날 두 단계를 다시 돈다(멱등 · §3.2 7). 보고서로 `deadline.phase_done` · `deadline.group_failed` · `deadline.bucket_failed` 로그 |
| `app/worker/src/main/kotlin/…/app/worker/deadline/DeadlineProperties.kt` | 새로 | `worker.deadline.enabled`(true — false 면 스케줄러를 띄우지 않는다) · `instance-index` · `instance-count` · `bucket-parallelism`(32) · `inflight`(256) · `retry`(3) · `retry-backoff`(100ms — 두 배씩) · `early-retry-delay`(20ms) · `early-retry-max`(200) |
| `app/worker/src/main/kotlin/…/app/worker/deadline/DeadlineConfiguration.kt` | 새로 | `DeadlineService` 빈(`DeadlineProperties` → `DeadlineService.Options`) |
| `app/worker/src/main/kotlin/…/app/worker/persister/…` | 수정 | persister 소비 루프(platform 일감 — `StreamPersister`)가 `group.decided` · `group.ranking_final` 을 커밋한 뒤 `ResultCache.putIfNotOlder` 를 부르는 한 곳 |
| `core/domain/src/testFixtures/kotlin/…/core/testfixture/RankingCases.kt` · `src/testFixtures/resources/ranking-cases.json` | 새로 | ORACLE §1.1 경계 사례 표 — Kotlin · Lua 테스트가 같은 파일을 읽는다. JSON 은 표 그대로(시각은 게임 날짜 KST `HH:mm:ss.SSSSSS` · `expectedRank` · `expectedOrder` · `expectedLate`), `RankingCases.key(member)` 가 µs 와 지각(`startedAt > lateAfter`)을 계산해 `RankingKey` 를 만든다 |
| `core/domain/src/testFixtures/kotlin/…/core/testfixture/RankingRandomGroups.kt` | 새로 | ORACLE O3 무작위 그룹 — 시드 `20261111` · 10,000 그룹 · 인원 2~10 · 상태 5종 · 진행도 -1~3 · 그룹마다 도착 시각 후보 1~3개(1µs 이웃)로 동시각을 일부러 만든다 · 패스 · 지각. `RankingKey` 불변식을 지킨다. Kotlin · Lua 테스트가 같은 입력을 받는다 |
| `core/domain/src/test/kotlin/…/core/domain/ranking/RankingTest.kt` | 새로 | ORACLE O1 · O4 와 비교 함수 성질(반대칭 · 추이 · 경계 표가 못 잡는 분기). O3 의 Kotlin 쪽(생성기 결정성 · 키 분포)과 I7(경쟁 순위 성질 · 입력 순서 무관) |
| `infra/redis/src/test/kotlin/…/infra/redis/deadline/*Test.kt` · `src/test/resources/lua/rank_only.lua` · `rank_hash.lua` | 새로 | Testcontainers `redis:8-alpine` — ORACLE O2 · O3 · O5 ~ O15 · O27. `DeadlineLuaTest`(O5 ~ O15)는 §1.2 시나리오를 그룹 Hash 에 쓰고 Lua `deadline` 을 경계 `D = TIME − …` 로 직접 부르며, 채널 메시지는 Lettuce `SSUBSCRIBE` 로 센다 · 유예 풀이는 solve-signal 이 쓰는 필드를 직접 쓴다. `LuaRankingTest`(O2 · O3 · O4 · I6 · I7)는 `RankingCases` · `RankingRandomGroups`(testFixtures)로 Kotlin 과 같은 입력을 만든다. `rank_hash.lua` 는 `fromHash` · `encode` 를 그룹 Hash 로 확인한다 |
| `app/api/src/test/kotlin/…/app/api/deadlineresult/*Test.kt` | 새로 | Testcontainers Redis + `postgres:18-alpine` — ORACLE O18 ~ O26 · O29 ~ O31 |
| `app/worker/src/test/kotlin/…/app/worker/deadline/*Test.kt` | 새로 | `DeadlineServiceTest`(O16 · I1 ~ I5 — 유예 풀이는 Lua `solve` 를 직접 부른다) · `DeadlineThroughputTest`(O17) · `DeadlineSchedulerTest`(08:00 · 08:00:03 띄우기 · 재기동 다시 돌기 · 다시 돌 날짜 규칙) · `ResultCacheWriteTest`(O28 — T-DR-6) |

## 2. 데이터 모델
새 테이블 · 새 Redis 키는 없다. 키 이름은 HLD platform §4.1 그대로. `<b>` = `gid mod 1024`, `<date>` = KST 게임 날짜 `YYYY-MM-DD`.

### 2.1 Redis 키
| 키 | 이 기능이 | 언제 |
|---|---|---|
| `g:{<b>}:<gid>:<date>` (그룹 Hash) | 읽기 · 쓰기 | Lua `deadline` · 결과 조회(`HMGET`) |
| `g:{<b>}:<gid>:<date>:members` (명단 Hash — `<uid>` → 로그인 아이디 · `#size` · `#name`) | 읽기 | Lua `deadline` |
| `day:{<b>}:<date>` (Set, 값 = gid) | 읽기 | `worker: deadline` 이 `SSCAN` |
| `ev:{<b>}` (Stream) | `XADD` | Lua `deadline` — `group.decided` · `group.ranking_final`(필드는 HLD platform §4.2) |
| `ch:{<b>}:<gid>` (sharded 채널) | `SPUBLISH` | Lua `deadline` — 메시지 `type` `group.pending` · `group.decided` · `group.ranking_final`(본문은 realtime-board LLD §2.3) |
| `result:{<gid>}` (String, JSON) | 읽기 · 쓰기 | 결과 조회(`GET` · `SET NX EX 90000`) · persister(`result_cache_put`) |

### 2.2 그룹 Hash 필드
필드 이름의 정본은 [LLD game-session](game-session.md) §2.2 다(그 기능이 07:00 에 Hash 를 만든다). 아래는 그 이름 중 이 기능이 읽고 쓰는 것이다(`<uid>` = 유저 내부 id).

| 필드 | 값 | 이 기능이 | 비고 |
|---|---|---|---|
| `<uid>:lid` | 로그인 아이디 | 읽기 | 랭킹 · 결과의 `userId`(D5) |
| `<uid>:st` | `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` | 읽기 · 쓰기(`NOT_STARTED` · `IN_PROGRESS` → `FAILED`) | D17 |
| `<uid>:p` | `-1` ~ `3` | 읽기 | |
| `<uid>:late` | `1` · `0` (시작 전엔 없음) | 읽기 | D8 — 시작 시각 `> 07:00:03` |
| `<uid>:ls` | 마지막 풀이 도착 — 마이크로초 십진 정수 문자열(Redis `TIME` · Lua 에서는 `string.format('%.0f', t)` 로 쓴다 — `tostring` 은 지수 표기라 µs 를 잃는다) · 없음 | 읽기 | 랭킹 2순위 |
| `<uid>:pn` | 패스 번호 `1` · `2` · `3` · 없음 | 읽기 | 랭킹 3 · 3′순위 |
| `<uid>:cur` | 지금 문제 PK(번호는 언제나 진행도 + 1) · 없음 | 읽기 | 미룸 판정 — 「3번 지금 문제가 있음」 = `p == 2` 이고 `cur` 있음 |
| `version` | 정수(07:00 `roster` 가 `0` 으로 만든다) | 읽기 · `HINCRBY 1` | 바뀔 때마다. SSE `id:`. 결과 조회는 이 필드로 「오늘 Hash 있음」을 판정한다 |
| `pending` | `1` · 없음 | 쓰기 · 지우기 | 1차에 미룬 그룹 |
| `result` | `SUCCEEDED` · `FAILED_IMMEDIATE` · `FAILED_DEADLINE` · 없음 | 읽기 · `HSETNX`(`FAILED_DEADLINE`) | NFR-2. 앞의 둘은 solve-signal 이 쓴다 |
| `decidedAt` | 마이크로초 정수 문자열 | 쓰기 | 결과와 함께 |
| `ranking` | 랭킹 JSON(아래 형식) | 쓰기 | 잠정 · 확정 |
| `rankingFinal` | `1` · `0` | 쓰기 | 2차(또는 08:00:03 뒤 처리)에 `1`. 한 번 `1` 이면 바뀌지 않는다 |

그룹원 목록은 명단 Hash `g:{<b>}:<gid>:<date>:members` 의 `#` 로 시작하지 않는 필드(= `<uid>`)다(game-session §2.2).

- 시각은 모두 **마이크로초 정수를 문자열로** 둔다. Redis `cjson` 은 숫자를 14자리로 줄여 16자리 마이크로초를 잃으므로 JSON 안에서도 문자열이다.
- 랭킹 JSON 한 줄: `{"id":"<uid>","u":"<login>","r":<rank>,"p":<-1~3>,"s":"<status>","l":"<µs>"|null,"n":<1~3>|null}` — 순위 오름차순 · 같은 순위는 `u` 바이트 순. 한 줄 안 키 순서는 정해지지 않는다(cjson) — 쓰는 쪽(persister · realtime-board · 테스트)은 JSON 으로 읽어 비교하고 문자열로 비교하지 않는다.

### 2.3 PostgreSQL (읽기만 — LLD platform §2.1 DDL)
지난 결과 조회:
```sql
-- 1) 이 그룹의 가장 최근 결과
SELECT game_date, result, completed_at
FROM group_results
WHERE group_id = :gid
ORDER BY game_date DESC
LIMIT 1;

-- 2) 그 날의 랭킹 스냅샷
SELECT u.login_id, m.rank, m.progress, m.status, m.last_solved_at, m.pass_question_no
FROM group_result_members m
JOIN users u ON u.id = m.user_id
WHERE m.game_date = :gameDate AND m.group_id = :gid
ORDER BY m.rank, u.login_id COLLATE "C";
```
| 필요한 인덱스 | 이유 | 상태 |
|---|---|---|
| `CREATE INDEX group_results_group_idx ON group_results (group_id, game_date DESC);` | 쿼리 1 은 `group_id` 로 모든 파티션을 본다 — PK `(game_date, group_id)` 로는 파티션마다 전체 탐색 | LLD platform v3 DDL 에 있음 |
| `CREATE INDEX group_result_members_group_idx ON group_result_members (game_date, group_id);` | 쿼리 2 는 PK `(game_date, user_id)` 로 못 찾는다 | 같음 |

- `result` → `groupStatus`: `SUCCEEDED` → `SUCCEEDED`, `FAILED_IMMEDIATE` · `FAILED_DEADLINE` → `FAILED`. `completed_at` → `decidedAt`.
- `rankingFinal` 은 PostgreSQL 에 칸이 없다 — `now ≥ game_date 08:00:03 KST` 이면 `true`(PostgreSQL 에서 읽는 결과는 거의 언제나 지난 날이다). 오늘 결과를 PostgreSQL 에서 읽는 것은 그룹 Hash 가 사라진 경우뿐이다(§5).
- `progress` -1 → API `progress 0` · `started false`.

### 2.4 결과 캐시 값 `result:{<gid>}`
```json
{ "date": "2026-11-11", "groupStatus": "FAILED", "decidedAt": "2026-11-11T08:00:00.000412+09:00",
  "rankingFinal": true, "ranking": [ { "userId": "amy0", "rank": 1, "progress": 3, "status": "SUCCEEDED",
  "started": true, "lastSolvedAt": "2026-11-11T07:20:00.000000+09:00", "passedAtQuestionNo": null } ] }
```
`myRank` · `nextGameStartAt` 은 넣지 않는다(요청마다 붙인다). Kotlin 이 쓰고 Kotlin 이 읽는다 — Lua 는 `date` · `rankingFinal` 만 본다.

## 3. 처리 절차

### 3.1 시각
| 이름 | 값 | 계산 |
|---|---|---|
| `deadlineUs` | `<date>` 08:00:00 KST 의 마이크로초 | `GameProperties.deadlineTime` · `zone` |
| `graceEndUs` | `<date>` 08:00:03 KST | `deadlineUs + submitGrace` |
- 판정은 Lua 안 Redis `TIME` + `offsetUs`(`t`)로 한다. 두 경계는 ARGV 로 넘긴다 — 테스트는 경계를 지금 `TIME` 기준으로 잡거나 `game.clock-offset`(`GameProperties.clockOffset`)으로 옮겨 08:00 을 기다리지 않는다.
- 결과 조회의 「08:00 이후」(§3.3 1)는 `api` 의 `Clock` + `game.clock-offset` 이다(신호 판정이 아니라 화면 선택 — 경계 몇 ms 의 차이는 3의 직전 결과가 보이는 것뿐).

### 3.2 `worker: deadline` — 버킷 병렬 처리
1. `DeadlineScheduler` 가 그날 08:00:00 에 `runPhase(date, 1)`, 08:00:03 에 `runPhase(date, 2)` 를 **따로** 띄운다(1차가 끝나지 않았어도 2차는 시작한다 — 둘 다 멱등).
2. 맡을 버킷: `b mod instance-count == instance-index` 인 `b`(0~1023). 인스턴스 하나면 1,024개 전부.
3. 버킷을 채널에 넣고 코루틴 `bucket-parallelism`(32)개가 하나씩 꺼낸다.
4. 버킷 하나: `SSCAN day:{<b>}:<date> <cursor> COUNT 1000` 을 끝까지 → gid 마다 `EVALSHA deadline` 을 파이프라인으로(동시에 `inflight` 256개까지) 보낸다.
5. 응답 코드별 처리:
   | 코드 | 처리 |
   |---|---|
   | `EARLY` | 워커 시계가 Redis 보다 빠름 — 20ms 뒤 그 gid 만 다시(최대 200번) |
   | `PENDING` · `DECIDED` · `DECIDED_FINAL` · `FINALIZED` · `SKIPPED` · `NO_GROUP` | 셈만 한다 |
   | 오류(연결 · 타임아웃) | 그 gid 를 실패 목록에 넣고 버킷이 끝난 뒤 지수 백오프로 `retry`(3)번. 그래도 실패면 로그 `deadline.group_failed gid=… phase=…` |
   | `EARLY` 를 200번 넘게 받음 | 실패로 센다(`deadline.group_failed … reason=EARLY`) |
   - 버킷의 `SSCAN` 이 오류면 그 버킷을 처음부터 다시 돈다(`retry` 번 · 같은 백오프 — 이미 처리된 그룹은 `SKIPPED`). 그래도 실패면 로그 `deadline.bucket_failed bucket=… phase=…` 이고 단계 보고의 `failedBuckets` 에 센다.
6. 단계가 끝나면 로그 `deadline.phase_done date phase buckets groups pending decided decidedFinal finalized skipped noGroup failed failedBuckets elapsedMs`.
7. 재시작: 기동 때 `now ≥ 그날 08:00` 이고 `now < 다음 날 07:00` 이면 그날 1차 · 2차를 다시 돈다. 「그날」 = 08:00 이 이미 지난 가장 늦은 날짜(`deadlineAt(d) ≤ now` — 08:00 뒤 기동이면 오늘, 00:00 ~ 07:00 기동이면 어제). 1차는 곧바로, 2차는 `max(now, 그날 08:00:03)` 에 띄운다. 이미 처리된 그룹은 `SKIPPED` 로 끝난다. 그다음 날부터는 1 대로 기다린다.
8. 기다리기는 `game.now(clock)` 으로 남은 시간을 다시 재며 최대 1분씩 `delay` 한다 — 벽시계가 바뀌어도 1분 안에 맞춘다. 1차 · 2차는 각각 따로 코루틴으로 띄우고 다음 시각을 기다린다(앞 단계가 끝나기를 기다리지 않는다).
- 인스턴스를 여럿 띄워 버킷이 겹쳐도(설정 실수) Lua 가 멱등이라 결과는 같다 — 겹침은 비용만 늘린다.

### 3.3 결과 조회 `GET /api/v1/games/results/latest`
입력: 인증 주체(유저 내부 id · 로그인 아이디 · 그룹 id — access JWT 클레임, auth-signup). `now` = `Clock`, `today` = `now` 의 KST 날짜, `b = gid mod 1024`.
1. `now ≥ today 08:00` 이면 `HMGET g:{<b>}:<gid>:<today> version result decidedAt ranking rankingFinal`. Hash 가 있고(`version` 이 있음) `result` 가 없으면 → **PENDING**(`date = today`, `decidedAt null`, `ranking []`, `myRank null`, `rankingFinal false`).
2. (시각과 무관하게) 오늘 그룹 Hash 에 `result` 가 있으면 → 그 결과: `groupStatus` 는 §2.3 매핑, `decidedAt` · `ranking` 은 Hash 값, `rankingFinal = (rankingFinal == "1")`. 07:00 ~ 08:00 의 조기 확정(성공 · 즉시 실패)도 여기서 준다.
3. 그 밖 → `GET result:{<gid>}`. 있으면 그 값.
4. 없으면 → §2.3 쿼리 1 · 2. 행이 있으면 `LatestResult` 를 만들어 `SET result:{<gid>} <json> NX EX 90000` 후 준다.
5. 쿼리 1 에 행이 없으면 → `404 NO_RESULT_YET`. 캐시하지 않는다.
6. 응답마다 `myRank` = `ranking` 에서 `userId == 내 로그인 아이디` 인 줄의 `rank`, 없으면 `null`(그날 07:00 뒤 가입). `nextGameStartAt` = `now < today 07:00` 이면 `today 07:00`, 아니면 `today+1 07:00`(KST, `+09:00`).
- 1 · 2 는 Hash 를 한 번만 읽는다(`HMGET` 한 번으로 두 단계를 판정). 오늘 Hash 가 없으면(1명 그룹 · 07:00 전 · 만료) 바로 3.

### 3.4 결과 캐시 갱신 (persister 연결)
1. persister 가 `group.decided` 또는 `group.ranking_final` 을 PostgreSQL 에 커밋한다(LLD platform §3).
2. 커밋 뒤, 이벤트의 `date` · `result` · `decidedAt` · `ranking` · `rankingFinal` 로 §2.4 JSON 을 만들어 `EVALSHA result_cache_put 1 result:{<gid>} <date> <rankingFinal> <json> 90000`.
3. 반환 `0`(더 새 값이 있음)은 정상 — 셈만 한다. Redis 오류면 로그만 남기고 `XACK` 한다(캐시는 정본이 아니다 — 다음 조회가 PostgreSQL 로 채운다).

## 4. Lua 스크립트

### 4.1 `deadline`
| 입력 | 값 |
|---|---|
| `KEYS[1]` | `g:{<b>}:<gid>:<date>` |
| `KEYS[2]` | `g:{<b>}:<gid>:<date>:members` (명단 Hash — `HKEYS` 에서 `#` 필드를 뺀 것이 그룹원) |
| `KEYS[3]` | `ev:{<b>}` |
| `ARGV[1]` | `phase` — `1` · `2` |
| `ARGV[2]` | `date` — `YYYY-MM-DD` |
| `ARGV[3]` | `gid` |
| `ARGV[4]` | `deadlineUs` (08:00:00) |
| `ARGV[5]` | `graceEndUs` (08:00:03) |
| `ARGV[6]` | 채널 `ch:{<b>}:<gid>` (같은 해시태그 — 같은 슬롯) |
| `ARGV[7]` | `offsetUs` — 설정 `game.clock-offset`(`GameProperties.clockOffset`)을 µs 로(운영 0 — 모든 기능의 Lua 가 같은 이름 · 같은 방식) |

`phase` 가 `1` · `2` 가 아니거나 `deadlineUs` · `graceEndUs` · `offsetUs` · Hash `version` 이 십진 정수가 아니면 스크립트 오류로 끝낸다(조용히 읽지 않는다 — `common.now` 와 같은 태도).
그룹 Hash 는 있는데 명단 Hash 에 그룹원이 없으면 빈 랭킹으로 확정하지 않고 오류로 끝낸다. Redis 는 Lua 오류 때 앞서 쓴 것을 되돌리지 않으므로, 확정(5.2)은 그룹원 칸 형식 검사(`ranking.fromHash`)와 `HSETNX result`(0 이면 오류)를 **상태를 바꾸기 전에** 한다 — 오류로 끝난 실행은 Hash · Stream · 채널에 아무것도 남기지 않는다.

판정 순서 — 위에서부터 보고 처음 걸리는 것으로 끝낸다. `t` = `TIME` 의 `초 × 1e6 + µs` + `offsetUs`.
1. `t < deadlineUs`, 또는 `phase == 2` 이고 `t < graceEndUs` → `EARLY`(아무것도 안 바꿈).
2. `KEYS[1]` 이 없음 → `NO_GROUP`.
3. `final = (t ≥ graceEndUs)` — 08:00:03 뒤에 처리되는 그룹은 `phase` 와 무관하게 1차 · 2차를 한 번에 한다.
4. `rankingFinal == "1"` → `SKIPPED`.
5. `result` 가 **없음**:
   1. `not final` 이고, 그룹원 중 `status == IN_PROGRESS` · `progress == 2` · `cur` 있음이 한 명 이상 → 미룬다. `pending` 이 이미 `1` 이면 그대로 `PENDING`. 아니면 `HSET pending 1` · `HINCRBY version 1` · `SPUBLISH`(`type` `group.pending`) → `PENDING`.
   2. 그 밖 → **확정**: `status` 가 `NOT_STARTED` · `IN_PROGRESS` 인 그룹원을 `FAILED` 로 · `HSETNX result FAILED_DEADLINE` · `HSET decidedAt t` · `ranking = rank(그룹원)`(§4.2 — 상태를 `FAILED` 로 바꾼 **뒤에** `ranking.fromHash` 로 읽어야 JSON `s` 가 바뀐 상태다) · `HSET ranking` · `HSET rankingFinal (final ? 1 : 0)` · `HDEL pending` · `HINCRBY version 1` · `XADD group.decided` · `SPUBLISH`(`type` `group.decided`) → `final` 이면 `DECIDED_FINAL`, 아니면 `DECIDED`.
6. `result` 가 **있음**:
   1. `not final` → `SKIPPED`(08:00 전 확정 그룹 · 유예 중 확정된 미룬 그룹의 1차 재실행).
   2. `final` → **랭킹 확정**: 상태는 바꾸지 않고 `ranking = rank(그룹원)` · `HSET ranking` · `HSET rankingFinal 1` · `HDEL pending`(결과를 쓴 `solve` 가 지웠어야 하지만 ORACLE I4 를 여기서도 지킨다) · `HINCRBY version 1` · `XADD group.ranking_final` · `SPUBLISH`(`type` `group.ranking_final`) → `FINALIZED`.

출력: `{code, version}` (`version` 은 끝난 뒤 값, `NO_GROUP` · `EARLY` 는 `0`).

`XADD ev:{<b>} * …` 필드:
공통 필드 `type` · `date` · `gid` · `uid` · `lid` · `version` · `at`(HLD platform §4.2) — 그룹 단위 일이라 `uid` · `lid` 는 빈 문자열, `at` = `t`.
| 이벤트 | 더 싣는 필드 |
|---|---|
| `group.decided` | `result` · `decidedAt`(µs) · `rankingFinal`(0 · 1) · `memberCount` · `ranking`(§2.2 JSON) |
| `group.ranking_final` | `result` · `decidedAt` · `rankingFinal`(1) · `ranking` |
- `DECIDED_FINAL` 은 `group.decided` 하나만 낸다(`rankingFinal 1`) — 같은 순간의 확정 랭킹을 두 번 보내지 않는다. persister 는 `rankingFinal 1` 인 `group.decided` 의 랭킹을 확정값으로 기록한다.

`SPUBLISH ARGV[6]` 내용(정본 realtime-board LLD §2.3): `{"type":"<group.pending|group.decided|group.ranking_final>","streamId":"<Stream id>"|null,"version":<version>,"date":"<date>","gid":"<gid>","uid":null,"at":"<t µs>","hash":<HGETALL 결과 객체>}` — realtime-board 가 보는 사람별로 SSE 를 만든다(`group.decided` → SSE `group.result`, realtime-board LLD §4.4). `group.pending` 은 Stream 에 남기지 않으므로 `streamId null`.

멱등: 같은 그룹 · 같은 단계를 몇 번 돌려도 두 번째부터는 `PENDING`(바꾸지 않음) · `SKIPPED` 다 — `version` · Stream · 채널에 아무것도 더하지 않는다.

### 4.2 랭킹 함수 (Lua `lib/ranking.lua` · Kotlin `Ranking`) — HLD §4
아래 의사 코드의 `cmp` · `bytecmp` · `rank` 는 Lua 에서 `ranking.cmp` · `ranking.bytecmp` · `ranking.rank` 다.
입력 한 줄 `m`: `login` · `cheat`(status == `CHEAT_FAILED`) · `p`(진행도 -1~3) · `l`(마지막 풀이 µs, 없으면 nil) · `n`(패스 번호, 없으면 nil) · `late`(boolean, 시작 전이면 false). `FAILED` 는 키가 아니다 — 진행도 그대로 비교한다.

```
-- 음수: a 가 위, 양수: b 가 위, 0: 동률
function cmp(a, b)
  if a.cheat ~= b.cheat then return a.cheat and 1 or -1 end       -- 0순위
  if a.cheat then return 0 end                                     -- 부정 실패끼리 동률(끝)
  if a.p ~= b.p then return (a.p > b.p) and -1 or 1 end            -- 1순위 진행도
  if a.p >= 1 then
    if a.l ~= b.l then return (a.l < b.l) and -1 or 1 end          -- 2순위 도착 시각(µs)
    if (a.n == nil) ~= (b.n == nil) then return (a.n == nil) and -1 or 1 end  -- 3순위 패스 안 씀
    if a.n ~= nil and a.n ~= b.n then return (a.n > b.n) and -1 or 1 end      -- 둘 다 씀: 큰 번호
    return 0
  elseif a.p == 0 then
    if a.late ~= b.late then return a.late and 1 or -1 end         -- 2′ 지각 아님
    if (a.n == nil) ~= (b.n == nil) then return (a.n == nil) and -1 or 1 end  -- 3′ 패스 안 씀
    return 0                                                        -- 둘 다 씀: 동률
  end
  return 0                                                          -- 진행도 -1 동률
end

function bytecmp(x, y)   -- 로그인 아이디 바이트 순(로케일 무관). Kotlin 은 String.compareTo(아이디가 ASCII 라 같다)
  ...string.byte 로 한 글자씩...
end

function rank(members)
  table.sort(members, function(a, b)
    local c = cmp(a, b); if c ~= 0 then return c < 0 end
    return bytecmp(a.login, b.login) < 0
  end)
  for i, m in ipairs(members) do                                   -- 경쟁 순위(1, 1, 3)
    if i > 1 and cmp(members[i-1], m) == 0 then m.r = members[i-1].r else m.r = i end
  end
  return members
end
```
Kotlin 은 같은 구조다: `compare(a, b): Int` · `rank(keys) = keys.sortedWith(compare then loginId).` 앞 줄과 `compare == 0` 이면 앞 순위, 아니면 `index + 1`.

Lua 에서는 모듈 `ranking`(로더 규약 — `ranking = {}` 로 시작)의 함수다: `ranking.cmp` · `ranking.bytecmp` · `ranking.rank` 와 그룹 Hash 를 잇는 두 함수.
| 함수 | 하는 일 |
|---|---|
| `ranking.fromHash(key, uids)` | 그룹 Hash 에서 그룹원마다 `HMGET <uid>:lid :st :p :late :ls :pn` → 입력 한 줄(`id` · `login` · `s` · `cheat` · `p` · `l` · `ls`(원래 문자열) · `n` · `late`). 칸 형식이 어긋나면 조용히 읽지 않고 스크립트 오류: `lid` · `st` 없음, `p` 가 -1~3 정수가 아님, `ls` 가 십진 정수가 아님(지수 표기 · 빈 값), `pn` 이 1~3 이 아님, `late` 가 `1` · `0` 이 아님, `p ≥ 1` 인데 `ls` 없음(Kotlin `RankingKey` 불변식과 같다) |
| `ranking.encode(members)` | `fromHash` → `rank` 를 거친 표 → §2.2 랭킹 JSON(표시 순서). `l` 은 Hash 의 문자열 그대로, 없는 `l` · `n` 은 `null`, 빈 그룹은 `[]`. `id` · `s` · `r` 이 없거나 `l` 은 있는데 `ls` 가 없는 줄(= `fromHash` 를 거치지 않은 줄)이면 스크립트 오류 |

테스트 래퍼 `rank_only.lua`(test 리소스): `ARGV` 에 그룹을 이어 붙인다 — 그룹마다 인원 `k`, 그 뒤 그룹원마다 `login · cheat(1|0) · p · l(µs · 없으면 '') · n(없으면 '') · late(1|0)` 6칸. 반환은 그룹마다 표시 순서의 `{login, r, …}`. 한 번에 여러 그룹(테스트는 500개)을 넣어 10,000 그룹을 20번에 돈다.
- 두 벌이 같다는 근거: 같은 경계 사례 파일(`ranking-cases.json`)과 같은 시드의 무작위 그룹 10,000개를 둘 다에 넣어 (로그인 아이디 → 순위)와 표시 순서가 모두 같은지 본다(ORACLE O1 ~ O3).
- 정렬은 `table.sort`(불안정)이지만 `cmp` 다음에 로그인 아이디로 끝까지 순서를 정하므로 결과가 하나다.
- `l` 은 문자열에서 `tonumber` — 마이크로초 1.8e15 < 2^53 이라 정확하다.

### 4.3 `result_cache_put`
| 입력 | 값 |
|---|---|
| `KEYS[1]` | `result:{<gid>}` |
| `ARGV[1]` | 들어온 결과의 게임 날짜 `YYYY-MM-DD` |
| `ARGV[2]` | 들어온 `rankingFinal` — `1` · `0` |
| `ARGV[3]` | §2.4 JSON |
| `ARGV[4]` | TTL 초 — `90000` |

판정 순서:
1. `cur = GET KEYS[1]`. 없으면 → 5.
2. `cd = cjson.decode(cur).date`. `cd > ARGV[1]`(날짜 문자열 비교) → `0`(쓰지 않음 — 옛 결과의 재처리).
3. `cd == ARGV[1]` 이고 캐시의 `rankingFinal == true` 이고 `ARGV[2] == "0"` → `0`(같은 날의 확정 랭킹을 잠정으로 덮지 않음).
4. (`cd < ARGV[1]` — 다음 게임 결과는 TTL 이 남아 있어도 덮는다)
5. `SET KEYS[1] ARGV[3] EX ARGV[4]` → `1`.

## 5. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| access 쿠키 없음 · 만료 · 위조 | 인증 필터(auth-signup) | `401 UNAUTHORIZED` |
| 확정된 결과가 한 번도 없음(가입 첫날 · 1명 그룹) | §3.3 5, 캐시하지 않음 | `404 NO_RESULT_YET` |
| 08:00 뒤 · 오늘 Hash 있음 · `result` 없음(미룸 · 워커 처리 전) | §3.3 1 | `200` `PENDING` |
| 07:00 ~ 08:00 · 결과 없음 | §3.3 3 — 직전 게임 결과(D3) | `200` 직전 결과 |
| 00:00 ~ 07:00 | 오늘 Hash 없음 → 3 · 4 — 전날 결과(D3) | `200` |
| 08:00 확정 · 08:00:03 전 | `rankingFinal false` · 유예 신호로 랭킹이 바뀔 수 있다(D17) | `200` |
| 오늘 그룹원이 아님(07:00 뒤 가입) | 그룹 결과는 주고 `myRank null` | `200` |
| 오늘 Hash 가 사라짐(Redis 비움 · 재적재 전) | 3 · 4 로 떨어진다 — PostgreSQL 의 가장 최근 결과(오늘 것이 기록됐으면 오늘) | `200` |
| Lua `deadline` 스크립트 오류(그룹원 칸 형식 · 명단 없음) | 아무것도 쓰지 않고 오류 — 워커는 연결 오류와 같이 재시도 뒤 `deadline.group_failed` 로그 | 조회는 `PENDING` |
| `worker: deadline` 이 끝내 그룹을 처리하지 못함 | 조회는 계속 `PENDING`. 로그 `deadline.group_failed` 로 찾아 재실행(멱등) | `200` `PENDING` |
| 워커 시계가 Redis 보다 빠름 | Lua `EARLY` → 20ms 뒤 재시도 | — |
| 1차가 08:00:03 까지 끝나지 않음 | 남은 그룹은 Lua 가 `final` 로 한 번에 확정(`DECIDED_FINAL`) | — |
| 결과 캐시 쓰기 실패 | 로그만 · 다음 조회가 PostgreSQL 로 채움 | — |
| PostgreSQL 오류(캐시 없음) | 그대로 오류 전파 | `500`(공통 처리) |
| 캐시 JSON 을 읽지 못함(형식 바뀜) | 캐시를 버리고(`DEL`) 4 로 | `200` |

## 6. 구현 순서
1. `Ranking` · `RankingKey`(Kotlin) + `ranking-cases.json` 픽스처 + `RankingTest` — ORACLE O1 · O4.
2. `lib/ranking.lua` + `RedisScripts` 로더(lib 붙이기) + `rank_only.lua` 테스트 래퍼 — ORACLE O2 · O3(Lua/Kotlin 일치).
3. `deadline.lua` + `RedisGroupDayRepository.runDeadline` — ORACLE O5 ~ O15.
4. `DeadlineService.runPhase`(버킷 병렬) + `DeadlineScheduler` · `DeadlineProperties` — ORACLE O16 · O17. `app:worker` 모듈 뼈대는 platform 일감이 먼저 만든다(LLD platform §5 3단계).
5. `Board.kt` 상태 값 정리 · `BoardDtos.kt`(`rankingFinal` · `cheatFailed` 삭제) — realtime-board 와 한 커밋으로 맞춘다.
6. 결과 조회: `GroupDayRepository.findResultState` · `RedisResultCache.get/putIfAbsent` · `RdbGameResultRepository.findLatest` · `BoardService.latestResult` · `BoardController` — ORACLE O18 ~ O26 · O29 ~ O31.
7. `result_cache_put.lua` + `RedisResultCache.putIfNotOlder` + persister 연결(커밋 뒤, platform 일감의 소비 루프에 처리기를 등록) — ORACLE O27 · O28.
8. §2.3 인덱스 2개는 LLD platform v3 DDL(`schema.sql`)에 있다 — 그 DDL 이 적용됐는지만 확인한다.
