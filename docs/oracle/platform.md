# ORACLE — platform (로컬 실행 · 저장소)

> 판 v3 · 2026-11-11 (v3: 공통 코드 바탕 기준 O8 ~ O12 — 시각 주입(앱 · Lua) · Lua 로더 · Redis 키 · 오류 코드 표(일감 T-PF-2) · v2: 통합 — I1 예외에 `AUTH_JWT_SECRET` 로컬 기본값(SPEC §2)) · 짝 문서: [HLD](../hld/platform.md) · [LLD](../lld/platform.md) · [SPEC](../spec/platform.md)

**세 줄 요약**
- 기준 12개(O1~O12) + 불변식 2개(I1~I2) — 로컬 실행 계약(SPEC)과 저장소 설정, 기능들이 함께 쓰는 공통 코드 바탕(시각 주입 · Lua 로더 · 키 · 오류 코드)이 「설계대로 됐다」를 판정한다.
- 확인 명령은 모두 레포 루트에서 돈다: `docker compose config` · `./iac/local.sh up` · `docker compose exec` · 백엔드 테스트.
- 판정 밖: 앱 수준 정합성(NFR-1~4)과 재시작 뒤 진행 상태 유지(NFR-5 의 앱 부분) — 기능별 ORACLE 이 정한다.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `docker compose config --quiet` | compose 정의 검사 | Docker Compose v2.20+ (`include` 지원) |
| `./iac/local.sh up` | 빌드 · 기동 · 헬스 확인 | Docker · curl |
| `docker compose exec -T redis redis-cli …` | Redis 설정 · 값 확인 | 서비스가 떠 있을 때 |
| `cd backend && ./gradlew test` | 백엔드 테스트(라우트 스모크 · 아키텍처 규칙) | JDK 21 |
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.config.*'` | 앱 시계 시각 주입(O8) | JDK 21 |
| `cd backend && ./gradlew :infra:redis:test --tests 'com.delightroom.mission.infra.redis.platform.*'` | Lua 시각 주입 · 로더 · 키(O9 ~ O11) | JDK 21 · Docker(Testcontainers `redis:8-alpine`) |
| `cd backend && ./gradlew :support:common:test` | 오류 코드 표(O12) | JDK 21 |

호스트에 JDK 21 이 없으면 레포 루트에서 `./iac/test.sh <gradle 인자>` 로 같은 명령을 컨테이너에서 돌린다.

**테스트 이름 규약** (O8 ~ O12)
- 파일: `core/domain/src/test/kotlin/…/core/config/GamePropertiesTest.kt` · `infra/redis/src/test/kotlin/…/infra/redis/platform/{CommonNowScriptTest,RedisScriptsTest,RedisKeysTest}.kt` · `support/common/src/test/kotlin/…/common/error/ErrorCodeContractTest.kt`.
- 테스트 메서드 이름은 기준 id 로 시작한다(`--tests '*O9*'` 로 하나만 돌린다).

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | compose 정의가 유효하고 서비스가 5개다 | SPEC §1 | `docker compose config --services` | `redis` · `postgres` · `api` · `realtime` · `web` 다섯 줄 |
| O2 | 루트에서 한 번에 뜬다 | prd §10 · SPEC §3 | `./iac/local.sh up` | 종료 0 · `api` · `realtime` · `web` 헬스 200 |
| O3 | Redis AOF 설정 | SPEC P1 | `docker compose exec -T redis redis-cli config get appendonly` · `… appendfsync` | `yes` · `everysec` |
| O4 | Redis 재시작 뒤 값 유지 | HLD §4 NFR-5 · SPEC P1 | `redis-cli set oracle:o4 1` → 2초 기다림 → `docker compose restart redis` → `redis-cli get oracle:o4` → `redis-cli del oracle:o4` | `get` 결과 `1` |
| O5 | 데이터 볼륨이 이름 있는 볼륨이다 | SPEC P2 | `docker volume ls` | `group-mission_postgres-data` · `group-mission_redis-data` 가 있다 |
| O6 | 백엔드 테스트 통과 | LLD §1 · `docs/api/openapi.yaml` | `cd backend && ./gradlew test` | 종료 0 |
| O7 | 내리기 | SPEC §3 | `./iac/local.sh down` 뒤 `docker compose ps -q` | 출력 없음 · O5 의 볼륨은 남아 있다 |
| O8 | 시각 주입 — 앱 시계 | HLD §8 · LLD §1 `GameProperties` | `GamePropertiesTest` — `game.clock-offset` 을 설정으로 묶고 `now(clock)` · `clockOffsetUs` 를 본다 | 설정 없으면 `clockOffset = 0` · `bucketCount = 1024`. `game.clock-offset=-30m` 이면 `now(clock)` = `clock.instant()` − 30분 · `clockOffsetUs` = −1,800,000,000. `PT1.0000019S` 는 µs 아래를 내림해 1,000,001 |
| O9 | 시각 주입 — Lua `common.now` | HLD §8 · LLD §1 `lib/common.lua` | `CommonNowScriptTest` — 탐침 스크립트가 `common.now(ARGV offsetUs)` 와 그 직후 `TIME`(µs)을 함께 돌려준다 | `offsetUs = 0` 이면 `0 ≤ TIME − now ≤ 1,000`. `offsetUs = −3,600,000,000`(−1시간)이면 `TIME + offsetUs − now` 가 0 ~ 1,000. 정수가 아닌 `offsetUs` 는 스크립트 오류(조용히 0 으로 읽지 않는다) |
| O10 | Lua 로더 — lib 붙이기 · `NOSCRIPT` 재적재 | LLD §1 `RedisScripts` | `RedisScriptsTest` — 서로를 부르는 lib 두 개(파일 이름 순서의 반대로 의존)와 본문으로 `eval` | 반환이 lib 두 개를 거친 값이다(순서 무관). 첫 호출 뒤 `SCRIPT EXISTS <sha>` = 1. `SCRIPT FLUSH` 뒤 같은 `eval` 이 성공하고 `SCRIPT EXISTS` = 1 로 돌아온다. 없는 스크립트 이름 · 식별자가 아닌 lib 이름은 예외 |
| O11 | Redis 키 이름 · 버킷 | HLD §4.1 · LLD §1 `RedisKeys` | `RedisKeysTest` | `bucket(gid) = gid mod 1024`(1025 → 1 · 1024 → 0). 키가 HLD §4.1 표와 글자 그대로 같다(`g:{1}:1025:2026-11-11` · `…:members` · `day:{1}:2026-11-11` · `ev:{1}` · `ch:{1}:1025` · `tk:{t}` · `sess:{7}` · `result:{1025}` · `bank:{7}:2026-11-11`). 한 그룹의 Lua 키 4개(그룹 Hash · 명단 · Stream · 채널)는 해시태그가 같다 |
| O12 | 오류 코드가 api-guide §4 표와 같다 | api-guide §4 · `ErrorCode` | `ErrorCodeContractTest` — `docs/api-guide.md` §4 표를 읽어 enum 과 견준다 | 코드 이름 · HTTP 상태 · 순서가 표와 같다(빠진 것 · 남는 것 0) |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 레포에 비밀 값이 없다 | `grep -rnE "api[_-]?key\|secret\|token\|password" iac compose.yaml` | 로컬 기본값(`mission-local` · `mission-local-jwt-secret-not-for-production` — SPEC §2) · 이름(`POSTGRES_PASSWORD` · `AUTH_JWT_SECRET`) 말고 실제 값 0 |
| I2 | 서비스 정의는 `iac/compose.yaml` 한 곳 | 루트 `compose.yaml` | `include` 와 `name` 만 있다 |

## 3. 판정 밖
- NFR-1 ~ NFR-4 와 NFR-5 의 앱 부분(재시작 뒤 진행 상태 · Stream → PostgreSQL 재적재) — 기능 구현과 함께 기능별 ORACLE 이 정한다.
- 250만 규모 부하(HLD §7) — 로컬 compose 로는 잴 수 없다. 부하 측정은 `solve-signal` · `realtime-board` 의 ORACLE 이 정한다.
- 운영 환경 확장(HLD §9) — 문서로만 설명한다(prd §10).
