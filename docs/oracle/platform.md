# ORACLE — platform (로컬 실행 · 저장소)

> 판 v3.6 · 2026-11-11 (v3.6: T-PF-6 — persister dead-letter(prd D20) 기준 O19 ~ O21 · O16 의 형식 오류 · 처리기 없는 `type` 을 dead-letter 로 · O13 테이블 9개(`persister_dead_letters`) · v3.5: T-PF-3 리뷰 반영 — O16 처리기 없는 `type` 은 ACK 안 함 · 실패 중 Stream 이 남는다 · Redis 오류 · 처리기 `Error` 경로 · O2 `worker` 는 `persister.started` 로그 · v3.4: T-PF-3 — O1 서비스 6개(`worker`) · O2 에 `worker` 상태 · persister 소비 루프 기준 O15 ~ O18(정상 · 실패와 재처리 · 회수 · 나눔과 등록) · v3.3: O13 리뷰 반영 — 오늘 · 내일 파티션 · 인덱스 정의 전체 비교 · 날짜는 DB 시계 · 재적용 뒤 문제 내용 다이제스트 · v3.2: 스키마 · 문제 시드 기준 O13 · O14(일감 T-PF-1) · v3.1: 리뷰 반영 — O9 입력 검사 · O10 재적재 횟수 · O6 환경 Docker · v3: 공통 코드 바탕 기준 O8 ~ O12 — 시각 주입(앱 · Lua) · Lua 로더 · Redis 키 · 오류 코드 표(일감 T-PF-2) · v2: 통합 — I1 예외에 `AUTH_JWT_SECRET` 로컬 기본값(SPEC §2)) · 짝 문서: [HLD](../hld/platform.md) · [LLD](../lld/platform.md) · [SPEC](../spec/platform.md)

**세 줄 요약**
- 기준 21개(O1~O21) + 불변식 2개(I1~I2) — 로컬 실행 계약(SPEC)과 저장소 설정, 기능들이 함께 쓰는 공통 코드 바탕(시각 주입 · Lua 로더 · 키 · 오류 코드), PostgreSQL 스키마 · 문제 시드, `worker: persister` 소비 루프와 dead-letter 가 「설계대로 됐다」를 판정한다.
- 확인 명령은 모두 레포 루트에서 돈다: `docker compose config` · `./iac/local.sh up` · `docker compose exec` · 백엔드 테스트.
- 판정 밖: 앱 수준 정합성(NFR-1~4)과 재시작 뒤 진행 상태 유지(NFR-5 의 앱 부분) — 기능별 ORACLE 이 정한다.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `docker compose config --quiet` | compose 정의 검사 | Docker Compose v2.20+ (`include` 지원) |
| `./iac/local.sh up` | 빌드 · 기동 · 헬스 확인 | Docker · curl |
| `docker compose exec -T redis redis-cli …` | Redis 설정 · 값 확인 | 서비스가 떠 있을 때 |
| `cd backend && ./gradlew test` | 백엔드 테스트(라우트 스모크 · 아키텍처 규칙 · O8 ~ O12) | JDK 21 · Docker(Testcontainers) |
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.config.*'` | 앱 시계 시각 주입(O8) | JDK 21 |
| `cd backend && ./gradlew :infra:redis:test --tests 'com.delightroom.mission.infra.redis.platform.*'` | Lua 시각 주입 · 로더 · 키(O9 ~ O11) | JDK 21 · Docker(Testcontainers `redis:8-alpine`) |
| `cd backend && ./gradlew :support:common:test` | 오류 코드 표(O12) | JDK 21 |
| `cd backend && ./gradlew :app:worker:test --tests 'com.delightroom.mission.app.worker.persister.StreamPersister*'` | persister 소비 루프 · dead-letter(O15 ~ O21) — 시험용 처리기가 PostgreSQL 에 쓴다 | JDK 21 · Docker(Testcontainers `redis:8-alpine` · `postgres:18-alpine`) |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.platform.*'` | 스키마 · 문제 시드(O13 · O14) — `api` 기동이 적용한 결과를 카탈로그로 본다 | JDK 21 · Docker(Testcontainers `postgres:18-alpine`) |

호스트에 JDK 21 이 없으면 레포 루트에서 `./iac/test.sh <gradle 인자>` 로 같은 명령을 컨테이너에서 돌린다.

**테스트 이름 규약** (O8 ~ O21)
- 파일: `core/domain/src/test/kotlin/…/core/config/GamePropertiesTest.kt` · `infra/redis/src/test/kotlin/…/infra/redis/platform/{CommonNowScriptTest,RedisScriptsTest,RedisKeysTest}.kt` · `support/common/src/test/kotlin/…/common/error/ErrorCodeContractTest.kt` · `app/api/src/test/kotlin/…/app/api/platform/SchemaSeedTest.kt`(O13 · O14) · `app/worker/src/test/kotlin/…/app/worker/persister/{StreamPersisterTest,StreamPersisterAppTest,StreamPersisterFaultTest,StreamPersisterDeadLetterTest}.kt`(O15 ~ O21 — `Fault` 는 오류를 넣는 가짜 포트 · `DeadLetter` 는 실제 PostgreSQL `persister_dead_letters`).
- 테스트 메서드 이름은 기준 id 로 시작한다(`--tests '*O9*'` 로 하나만 돌린다).

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | compose 정의가 유효하고 서비스가 6개다 | SPEC §1 | `docker compose config --services` | `redis` · `postgres` · `api` · `realtime` · `worker` · `web` 여섯 줄 |
| O2 | 루트에서 한 번에 뜬다 | prd §10 · SPEC §3 | `./iac/local.sh up` | 종료 0 · `api` · `realtime` · `web` 헬스 200 · `worker` 가 `running` 이고 로그에 `persister.started` |
| O3 | Redis AOF 설정 | SPEC P1 | `docker compose exec -T redis redis-cli config get appendonly` · `… appendfsync` | `yes` · `everysec` |
| O4 | Redis 재시작 뒤 값 유지 | HLD §4 NFR-5 · SPEC P1 | `redis-cli set oracle:o4 1` → 2초 기다림 → `docker compose restart redis` → `redis-cli get oracle:o4` → `redis-cli del oracle:o4` | `get` 결과 `1` |
| O5 | 데이터 볼륨이 이름 있는 볼륨이다 | SPEC P2 | `docker volume ls` | `group-mission_postgres-data` · `group-mission_redis-data` 가 있다 |
| O6 | 백엔드 테스트 통과 | LLD §1 · `docs/api/openapi.yaml` | `cd backend && ./gradlew test` | 종료 0 |
| O7 | 내리기 | SPEC §3 | `./iac/local.sh down` 뒤 `docker compose ps -q` | 출력 없음 · O5 의 볼륨은 남아 있다 |
| O8 | 시각 주입 — 앱 시계 | HLD §8 · LLD §1 `GameProperties` | `GamePropertiesTest` — `game.clock-offset` 을 설정으로 묶고 `now(clock)` · `clockOffsetUs` 를 본다 | 설정 없으면 `clockOffset = 0` · `bucketCount = 1024`(0 이하는 기동 실패). `game.clock-offset=-30m` 이면 `now(clock)` = `clock.instant()` − 30분 · `clockOffsetUs` = −1,800,000,000. `PT1.0000019S` 는 µs 아래를 내림해 1,000,001 |
| O9 | 시각 주입 — Lua `common.now` | HLD §8 · LLD §1 `lib/common.lua` | `CommonNowScriptTest` — 탐침 스크립트가 `common.now(ARGV offsetUs)` 와 그 직후 `TIME`(µs)을 함께 돌려준다 | `offsetUs = 0` 이면 `0 ≤ TIME − now ≤ 1,000`. `offsetUs = −3,600,000,000`(−1시간)이면 `TIME + offsetUs − now` 가 0 ~ 1,000. 십진 정수가 아닌 `offsetUs`(빈 값 · `abc` · `1.5` · `1e3` · `0x10`)는 스크립트 오류(조용히 읽지 않는다) |
| O10 | Lua 로더 — lib 붙이기 · `NOSCRIPT` 재적재 | LLD §1 `RedisScripts` | `RedisScriptsTest` — 서로를 부르는 lib 두 개(파일 이름 순서의 반대로 의존)와 본문으로 `eval` | 반환이 lib 두 개를 거친 값이다(순서 무관). 첫 호출 뒤 `SCRIPT EXISTS <sha>` = 1. 같은 스크립트를 두 번 불러도 `SCRIPT LOAD` 는 1회(Redis `commandstats`). `SCRIPT FLUSH` 뒤 같은 `eval` 이 성공하고 `SCRIPT LOAD` 가 1회 더 · `SCRIPT EXISTS` = 1 로 돌아온다. 없는 스크립트 이름은 예외, 식별자가 아닌 lib 이름은 로더를 만들 때 예외 |
| O11 | Redis 키 이름 · 버킷 | HLD §4.1 · LLD §1 `RedisKeys` | `RedisKeysTest` | `bucket(gid) = gid mod 1024`(1025 → 1 · 1024 → 0). 키가 HLD §4.1 표와 글자 그대로 같다(`g:{1}:1025:2026-11-11` · `…:members` · `day:{1}:2026-11-11` · `ev:{1}` · `ch:{1}:1025` · `tk:{t}` · `sess:{7}` · `result:{1025}` · `bank:{7}:2026-11-11`). 한 그룹의 Lua 키 4개(그룹 Hash · 명단 · Stream · 채널)는 해시태그가 같다 |
| O12 | 오류 코드가 api-guide §4 표와 같다 | api-guide §4 · `ErrorCode` | `ErrorCodeContractTest` — `docs/api-guide.md` §4 표를 읽어 enum 과 견준다 | 코드 이름 · HTTP 상태 · 순서가 표와 같다(빠진 것 · 남는 것 0) |
| O13 | 스키마가 LLD §2.1 대로 서고 다시 적용해도 같다 | LLD §2.1 · `schema.sql` | `SchemaSeedTest` — `api` 기동 뒤 `pg_class` · `pg_index` · `pg_constraint` · `col_description` 을 본 다음, `schema.sql` · `data.sql` 을 한 번 더 적용하고 다시 본다 | 테이블 9개(`groups` · `users` · `questions` · `served_questions` · `daily_progress` · `solve_signals` · `group_results` · `group_result_members` · `persister_dead_letters`)가 있고 하루 단위 4개는 `game_date` 범위 파티션 부모, 나머지는 보통 테이블이다. 모든 칸에 `COMMENT` 가 있다. 이름 붙은 인덱스 5개(`users_group_joined_idx` · `questions_difficulty_idx` · `daily_progress_group_idx` · `group_results_group_idx`(`group_id, game_date DESC`) · `group_result_members_group_idx`)가 LLD 의 테이블 · 칸에 있다(`pg_indexes.indexdef` 전체가 같다). DB 시계의 KST 오늘 · 내일 파티션 8개(`<테이블>_YYYYMMDD` · 범위 [D, D+1))가 있다. 8개 테이블의 모든 칸에 `COMMENT` 가 있고 `daily_progress.status` 설명에 `MemberStatus` 가 있다. CHECK 제약 0개. 다시 적용해도 오류 없이 테이블 · 인덱스 · 파티션 · 문제 수와 문제 내용 다이제스트 · 최대 `questions.id` 가 같다 |
| O14 | 문제 시드 | LLD §2.2 · prd R-A2 · D7 | `SchemaSeedTest` — `questions` 를 읽어 Kotlin 으로 해시를 다시 계산한다 | 난이도 1 · 2 · 3 마다 120개(≥ 30 — game-session 고정 데이터) · 다른 난이도 0. `pk_hash` · `answer_hash` 는 `^[0-9a-f]{64}$` 이고 `pk_hash` 는 겹치지 않는다. 모든 행이 `answer_hash = SHA-256(UTF-8("<pk_hash>:<정규화한 answer>"))`(정규화 = 공백 전부 제거 → 소문자 → 앞뒤 공백 제거)를 만족한다. 분야는 4종 안이고 난이도마다 4종이 다 있다 |
| O15 | persister — 정상 처리 · 순서 · ACK · 트림 | LLD §3.1 | `StreamPersisterTest` — 버킷 3개에 메시지 30개씩 `XADD`(그룹이 생기기 전 것 포함) · 시험용 처리기가 한 트랜잭션으로 `(bucket, seq)` 행을 쓴다 | 행 90개 = 메시지 수(빠짐 · 겹침 0). 버킷마다 처리 순서 = `XADD` 순서. 모든 버킷 `XPENDING` 0 · Stream 길이 ≤ 1(마지막 하나만 — 트림) |
| O16 | persister — 처리기 실패 · 재처리 | LLD §3.1 처리기 계약 · 레인 4 | 버킷 하나의 3번째 메시지에서 처리기가 행을 쓴 뒤 예외(롤백)를 3번 넘게 낸 다음 성공(이 시험은 `dead-letter-after` 를 크게 두어 dead-letter 로 가지 않게 한다). 같은 버킷에 뒤 메시지 5개 | 실패하는 동안 그 메시지 · 뒤 메시지 모두 ACK 안 됨(`XPENDING` ≥ 1) · 뒤 메시지 행 0 · Stream 에 실패한 메시지와 뒤 메시지가 남아 있다(트림이 pending 을 지우지 않는다). 실패가 멈추면 같은 메시지부터 Stream 순서대로 처리돼 행 = 메시지 수 · 같은 `(bucket, seq)` 1행(롤백된 쓰기 0) · `XPENDING` 0. 다른 버킷은 그동안 막히지 않는다. 처리기 없는 `type` 은 문턱 전까지 ACK 되지 않고 그 버킷의 뒤 메시지도 처리되지 않는다(처리기를 붙여 다시 띄우면 그 메시지부터 처리 · `XPENDING` 0 — 문턱 뒤는 O19). 오류를 넣는 가짜 포트로: 읽기 응답을 잃거나 ACK 가 실패해도 다음 걸음은 자기 PEL 부터라 처리 순서 = Stream 순서, 처리기가 `Error` 를 던져도 레인이 살아 다시 처리한다 |
| O17 | persister — 기동 때 회수 | LLD §3.1 레인 1 · 2 | 루프를 띄우기 전에 ① 자기 소비자 이름으로 읽고 ACK 안 한 메시지 ② 다른 소비자(`worker-9`)가 읽고 ACK 안 한 메시지를 만든다(`claim-min-idle` 은 시험에서 1초) | 기동 뒤 ①·② 모두 처리 · 각 1행 · `XPENDING` 0 · `XPENDING` 소비자 목록에 `worker-9` 의 pending 0 |
| O18 | persister — 인스턴스 나눔 · 처리기 등록 | LLD §3.1 맡는 버킷 · 처리기 계약 | 같은 Redis 에 `instance-count=2` 인 루프 둘(index 0 · 1)을 띄우고 버킷 8개에 메시지를 넣는다. 같은 `type` 처리기 둘을 등록해 루프를 만든다 | 각 메시지는 한 인스턴스에서만 처리(행 = 메시지 수) · 짝수 버킷은 index 0, 홀수는 index 1 이 처리. 처리기 중복이면 `IllegalStateException`(기동 실패) |
| O19 | persister — 처리하지 못하는 메시지는 dead-letter 1행 · ACK · 다음 메시지 | prd D20 · LLD §3.1 dead-letter · §2.1 `persister_dead_letters` | `StreamPersisterDeadLetterTest` — 실제 PostgreSQL · Redis, `dead-letter-after=3`. 버킷 하나에 ① 처리기가 늘 예외(`IllegalStateException`)를 내는 메시지 ② 처리기 없는 `type` ③ `MalformedEventException` 을 내는 메시지를 정상 메시지 사이에 넣는다 | ① · ② 는 처리기가 정확히 3번 시도된 뒤 `persister_dead_letters` 에 각 1행 — `bucket` · `stream_id` · `event_type` · `fields`(원본 칸 전체가 JSON 으로 같다) · `failure_count = 3` · `last_error`(① 예외 메시지 · ② `unhandled type`) · `first_failed_at ≤ last_failed_at`. ③ 은 1번 시도 뒤 1행(`failure_count = 1`). 정상 메시지는 모두 Stream 순서대로 처리(행 = 정상 메시지 수) · 그 버킷 `XPENDING` 0 · 트림(Stream 길이 ≤ 1) |
| O20 | persister — 같은 메시지를 다시 쌓아도 한 행 | LLD §3.1 dead-letter 5 · prd D20 | ⓐ `DeadLetterStore.put` 에 같은 (bucket, id) 를 실패 수 · 시각을 바꿔 두 번 넘긴다(실제 PostgreSQL). ⓑ 가짜 Stream 으로 dead-letter 를 쓴 직후의 ACK 를 실패시킨다(`StreamPersisterFaultTest`) | ⓐ 행 1개 · `failure_count` = 큰 값 · `first_failed_at` = 이른 값 · `last_failed_at` = 늦은 값 · `fields` 그대로. ⓑ 같은 메시지를 다시 받아 다시 쌓지만 가짜 저장소의 (bucket, id) 는 1개 · 그 메시지 `XPENDING` 0 · 뒤 메시지는 Stream 순서대로 1번씩 처리 |
| O21 | persister — DB 연결 장애는 dead-letter 로 보내지 않고 ACK 하지 않는다 | prd D20 · LLD §3.1 dead-letter 1 · 4 | 가짜 Stream · 가짜 저장소(`StreamPersisterFaultTest`, `dead-letter-after=3`). ⓐ 처리기가 `DataAccessResourceFailureException`(원인 사슬에 있는 경우 포함)을 10번 낸 뒤 성공 ⓑ 처리기가 늘 센 실패를 내고 `DeadLetterStore.put` 이 처음 2번 `DataAccessResourceFailureException` 을 낸다 | ⓐ dead-letter 0 · 실패 동안 그 메시지 · 뒤 메시지 ACK 안 됨 · 회복 뒤 같은 메시지부터 Stream 순서대로 처리 · `XPENDING` 0. ⓑ `put` 이 실패하는 동안 그 메시지 ACK 안 됨 · 뒤 메시지 처리 안 됨, `put` 이 성공하면 dead-letter 1개 · ACK · 뒤 메시지 처리(유실 0) |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 레포에 비밀 값이 없다 | `grep -rnE "api[_-]?key\|secret\|token\|password" iac compose.yaml` | 로컬 기본값(`mission-local` · `mission-local-jwt-secret-not-for-production` — SPEC §2) · 이름(`POSTGRES_PASSWORD` · `AUTH_JWT_SECRET`) 말고 실제 값 0 |
| I2 | 서비스 정의는 `iac/compose.yaml` 한 곳 | 루트 `compose.yaml` | `include` 와 `name` 만 있다 |

## 3. 판정 밖
- NFR-1 ~ NFR-4 와 NFR-5 의 앱 부분(재시작 뒤 진행 상태 · Stream → PostgreSQL 재적재) — 기능 구현과 함께 기능별 ORACLE 이 정한다.
- 250만 규모 부하(HLD §7) — 로컬 compose 로는 잴 수 없다. 부하 측정은 `solve-signal` · `realtime-board` 의 ORACLE 이 정한다.
- 운영 환경 확장(HLD §9) — 문서로만 설명한다(prd §10).
