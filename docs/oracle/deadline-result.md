# ORACLE — deadline-result

> 판 v2 · 2026-11-11 (v2: 통합 — 채널 메시지 `type` 을 realtime-board LLD §2.3 으로(`group.result` → `group.decided`, 키 `v` → `version`)) · 짝 문서: [HLD](../hld/deadline-result.md) · [LLD](../lld/deadline-result.md) · [SPEC](../spec/deadline-result.md) · [ORACLE](../oracle/deadline-result.md) · 근거: [PRD](../prd.md) v10 FR-6 · FR-7 · §6 · NFR-1 · NFR-2 · NFR-4 · D1 · D3 · D8 · D17

**세 줄 요약**
- 기준 31개(O1~O31) + 불변식 8개(I1~I8). 랭킹은 경계 사례 표(§1.1, 23행)를 Kotlin · Lua 둘 다에 넣어 같은 순위가 나와야 하고, 무작위 10,000 그룹에서도 불일치 0 이다.
- 마감은 08:00 1차 · 08:00:03 2차 시나리오(§1.2)를 Lua `deadline` 에 직접 돌려 판정한다 — 두 경계는 ARGV 라 테스트가 지금 Redis `TIME` 기준으로 잡는다(08:00 을 기다리지 않는다).
- 확인 명령은 모두 `cd backend && ./gradlew …` 이고 Testcontainers(Redis 8 · PostgreSQL 18)를 쓴다. 테스트는 아직 없다 — 이 기능의 일감이 세운다.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.domain.ranking.*'` | Kotlin 랭킹(경계 표 · 무작위 일치의 Kotlin 쪽) | JDK 21 · **이 기능의 일감이 세운다** — 파일 `core/domain/src/test/kotlin/…/core/domain/ranking/RankingTest.kt` |
| `cd backend && ./gradlew :infra:redis:test --tests 'com.delightroom.mission.infra.redis.deadline.*'` | Lua 랭킹 · Lua `deadline` · `result_cache_put` | JDK 21 · Docker(Testcontainers `redis:8-alpine`) · **이 기능의 일감이 세운다** — `infra/redis/src/test/kotlin/…/infra/redis/deadline/{LuaRankingTest,DeadlineLuaTest,ResultCachePutLuaTest}.kt` |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.deadlineresult.*'` | 결과 조회 API(Hash · 캐시 · PostgreSQL · 404 · 401) | JDK 21 · Docker(Redis · `postgres:18-alpine`) · `MutableClock` · **이 기능의 일감이 세운다** — `app/api/src/test/kotlin/…/app/api/deadlineresult/LatestResultApiTest.kt` |
| `cd backend && ./gradlew :app:worker:test --tests 'com.delightroom.mission.app.worker.deadline.*'` | `worker: deadline` 버킷 병렬 · 처리량 · persister 캐시 갱신 | JDK 21 · Docker · **이 기능의 일감이 세운다** — `app/worker/src/test/kotlin/…/app/worker/deadline/{DeadlineServiceTest,DeadlineThroughputTest,ResultCacheWriteTest}.kt` |
| `cd backend && ./gradlew test` | 전체(아키텍처 규칙 · 라우트 스모크 포함) | JDK 21 · Docker |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」. 테스트 이름은 기준 id 로 시작한다(예: `O5 1차 미룸 없음 그룹 확정`).

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | Kotlin 랭킹이 경계 표대로 | SPEC DR6 · DR7 · LLD §4.2 | `RankingTest` 가 `ranking-cases.json`(§1.1)의 그룹마다 `Ranking.rank` | 23행 모두 (userId → rank) 가 「기대 순위」와 같다 · 불일치 0 |
| O2 | Lua 랭킹이 경계 표대로 | SPEC DR6 · DR7 · LLD §4.2 | `LuaRankingTest` 가 같은 파일을 `rank_only.lua`(lib 붙임)로 `EVAL` | 23행 모두 같다 · 불일치 0 |
| O3 | Lua · Kotlin 일치(무작위) | HLD §7 · LLD §4.2 | 시드 `20261111` 로 그룹 10,000개(인원 2~10, 키 무작위 — 상태 5종 · 진행도 -1~3 · 도착 시각은 µs 단위로 일부러 겹침 · 패스 · 지각)를 만들어 둘 다에 넣는다 | 그룹마다 (userId → rank) 와 표시 순서(userId 목록)가 완전히 같다 · 불일치 그룹 0 |
| O4 | 같은 순위 안 표시 순서 · 경쟁 순위 | SPEC DR7 | §1.1 G2 의 `bo11` · `bo_1`(밑줄) | 순서 `bo11` → `bo_1`(바이트 순) · 다음 순위가 4(2, 2, 4) — Kotlin · Lua 둘 다 |
| O5 | 1차: 미룸 대상 없는 그룹 확정 | SPEC DR3 · LLD §4.1 5.2 | §1.2 S1 | 반환 `DECIDED` · `result = FAILED_DEADLINE` · 그룹원 중 `NOT_STARTED`/`IN_PROGRESS` 0명 · `rankingFinal = 0` · `version` +1 · `ev` 에 `group.decided` 1건 · 채널 메시지 `type = group.decided` 1건 |
| O6 | 1차: 3번 진행 중 그룹 미룸 | SPEC DR3 · LLD §4.1 5.1 | §1.2 S3 | 반환 `PENDING` · `pending = 1` · `result` 없음 · 그룹원 상태 그대로 · `version` +1 · `ev` 0건 · 채널 메시지 `type = group.pending` 1건(`streamId null`) |
| O7 | 미룸 조건의 경계 | LLD §2.2 `cur` · §4.1 5.1 | §1.2 S6 · S7 · 진행도 1 + `cur` 있음 | 셋 다 미루지 않고 `DECIDED` |
| O8 | 유예 반영 뒤 2차 랭킹 확정 | SPEC DR5 · LLD §4.1 6.2 | §1.2 S2 | 2차 반환 `FINALIZED` · `result` 그대로 `FAILED_DEADLINE` · `decidedAt` 그대로 · `rankingFinal = 1` · 랭킹에 유예 풀이 반영(`hal0` 진행도 2) · `group.ranking_final` 1건 |
| O9 | 2차: 미룬 그룹 확정 | SPEC DR3 · LLD §4.1 5.2 | §1.2 S5 | 반환 `DECIDED_FINAL` · `FAILED_DEADLINE` · 3번 풀던 그룹원 `FAILED` · `rankingFinal = 1` · `pending` 없음 · `ev` 에 `group.decided`(rankingFinal 1) 1건 · `group.ranking_final` 0건 |
| O10 | 미룬 그룹이 유예 안에 성공 | SPEC DR3 · DR5 | §1.2 S4 | 2차 반환 `FINALIZED` · `result = SUCCEEDED`(solve-signal 이 씀) · `rankingFinal = 1` |
| O11 | 08:00:03 뒤 처리는 한 번에 | HLD §3 처리 전 조회 · LLD §4.1 3 | §1.2 S8 | `phase=1` 인데 반환 `DECIDED_FINAL` · `rankingFinal = 1` · `pending` 을 거치지 않음 |
| O12 | 시각 전 실행은 아무것도 안 함 | LLD §4.1 1 | §1.2 S10 | 반환 `EARLY` · Hash 전체(`HGETALL`) 실행 전과 같음 · `ev` · 채널 0건 |
| O13 | 같은 단계 재실행 멱등 | SPEC DR4 · LLD §4.1 멱등 | S1 · S3 · S2 의 각 단계를 3번씩 | 두 번째부터 반환 `SKIPPED`(S3 1차는 `PENDING`) · `version` · `ev` 길이 · 채널 메시지 수가 첫 실행 뒤와 같다 |
| O14 | 08:00 전 확정 그룹 | LLD §4.1 6.1 | §1.2 S12 | 1차 `SKIPPED` · Hash 그대로. 2차는 `rankingFinal` 이 `1` 이면 `SKIPPED`, `0` 이면 `FINALIZED` |
| O15 | 그룹 Hash 없음 | LLD §4.1 2 | 없는 gid 로 실행 | 반환 `NO_GROUP` · 키가 생기지 않는다 |
| O16 | 버킷 병렬이 모든 그룹을 한 번씩 | LLD §3.2 | 1,024 버킷에 그룹 5,000개(버킷 = gid mod 1024) · 인스턴스 2개(`instance-index` 0 · 1)로 1차 | 모든 그룹 `result` 또는 `pending` 있음 · `ev` 의 `group.decided` 수 = 확정 그룹 수(중복 0) · 각 인스턴스가 맡은 버킷 수 512 |
| O17 | 1차 처리량(로컬) | HLD §7 | Redis 컨테이너 하나 · 그룹 10,000개 × 그룹원 5명 · 기본 설정 | `runPhase(1)` 완료 ≤ 5초 · 실패 0 |
| O18 | 조회: PENDING 모양 | SPEC DR1 ① · DR2 | `MutableClock` 08:00:01 · S3 의 1차 뒤 `GET …/results/latest` | `200` · 본문이 SPEC §2 PENDING 예시와 같은 키 · `groupStatus PENDING` · `decidedAt null` · `ranking []` · `myRank null` · `rankingFinal false` |
| O19 | 조회: 워커 처리 전도 PENDING | SPEC DR1 ① · DR2 | 08:00:01 · Hash 있음 · `deadline` 미실행 | `200` `PENDING` · 요청 뒤 Hash 의 `result` 여전히 없음(요청이 확정하지 않음) |
| O20 | 조회: 오늘 결과 · 잠정 → 확정 | SPEC DR1 ② · DR5 · DR9 | S2 — 1차 뒤(08:00:01) 조회, 2차 뒤(08:00:04) 조회 | 첫 응답 `FAILED` · `rankingFinal false` · 두 번째 `rankingFinal true` · 두 응답의 `date` · `decidedAt` 같음 · `myRank` = `ranking` 의 내 줄 `rank` |
| O21 | 조회: 캐시 적중 | SPEC DR1 ③ · LLD §3.3 3 | 오늘 Hash 없음 · `result:{gid}` 있음 · PostgreSQL 쿼리 수를 센다 | `200` · 본문 = 캐시 값 + `myRank` · `nextGameStartAt` · PostgreSQL 쿼리 0 |
| O22 | 조회: 캐시 없음 → PostgreSQL → 채움 | SPEC DR1 ③ · LLD §3.3 4 | 캐시 없음 · `group_results` · `group_result_members` 에 지난 결과 1건 | `200` · 본문 랭킹 = DB 행(rank · login_id · progress -1 → 0 · started false) · 요청 뒤 `result:{gid}` 있음 · `TTL` 89,990 ~ 90,000 |
| O23 | 조회: 결과 없음 | SPEC §3 `NO_RESULT_YET` · DR12 | 결과 행이 없는 그룹(1명 그룹 · 가입 첫날) | `404` · `code = NO_RESULT_YET` · `result:{gid}` 키 없음 |
| O24 | 조회: 인증 없음 | SPEC §3 `UNAUTHORIZED` | 쿠키 없이 · 만료 토큰 · 서명 틀린 토큰 | 셋 다 `401` · `code = UNAUTHORIZED` |
| O25 | 조회: 자정 뒤 직전 결과(D3) | SPEC DR1 ③ · DR10 | `MutableClock` 다음 날 00:30 · 전날 결과가 캐시 또는 DB 에 | `date` = 전날 · `nextGameStartAt` = 그날 07:00+09:00 |
| O26 | 다음 게임 시각 | SPEC DR10 | 06:59:59 · 07:00:00 · 08:01 에 조회 | 각각 오늘 07:00 · 내일 07:00 · 내일 07:00 |
| O27 | 결과 캐시 쓰기 Lua | LLD §4.3 | ① 캐시 11-11(TTL 1,000 남음)에 11-12 쓰기 ② 캐시 11-12 에 11-11 쓰기 ③ 캐시 11-12 확정에 11-12 잠정 쓰기 ④ 캐시 11-12 잠정에 11-12 확정 쓰기 | ① `1` · 값 11-12 · TTL ≥ 89,990 ② `0` · 값 그대로 ③ `0` · 그대로 ④ `1` · 확정 값 |
| O28 | persister 캐시 갱신 · 재처리 | HLD §3 결과 캐시 · LLD §3.4 | 11-11 `group.ranking_final` → 11-12 `group.decided` 처리 뒤 11-11 `group.decided` 를 다시 처리 | 캐시 날짜 11-12 그대로 · PostgreSQL 행 수 변화 0 · 캐시 쓰기는 커밋 뒤(커밋 실패를 흉내 내면 캐시 바뀌지 않음) |
| O29 | NFR-4 일치 | SPEC DR8 · HLD §5 | S2 2차 뒤 persister 처리 → 그룹원마다 API `rank` · Hash `ranking` 의 `r` · `group_result_members.rank` · 캐시 `rank` | 네 값이 모든 그룹원에서 같다 · 차이 0 |
| O30 | 그날 그룹원 아닌 유저 | SPEC DR9 | 07:00 뒤 가입한 같은 그룹 유저로 조회 | `200` · `ranking` 은 그룹원과 같음 · `myRank null` |
| O31 | 결과 상태 값 | SPEC §2 MemberEntry `status` · D17 | O20 · O22 · O29 의 모든 응답 | `ranking[].status` ∈ {`SUCCEEDED`, `CHEAT_FAILED`, `FAILED`} · `progress` ∈ 0~3 · `started=false` 인 줄은 `progress 0` |

### 1.1 랭킹 경계 사례 (`ranking-cases.json`)
시각은 게임 날짜 KST, 소수점 아래는 마이크로초. 「지각」은 `startedAt > 07:00:03.000000` 이면 예(D8). `—` 는 값 없음. 「기대 순위」는 그룹 안 경쟁 순위, 「표시」는 응답 배열의 순서.

| 그룹 | userId | 상태 | 진행도 | 시작 시각 → 지각 | 마지막 풀이 도착 | 패스 | 기대 순위 | 표시 | 보는 키 |
|---|---|---|---|---|---|---|---|---|---|
| G1 | `amy0` | SUCCEEDED | 3 | 07:00:01 → 아니오 | 07:20:00.000000 | — | 1 | 1 | 2순위 동시각 |
| G1 | `ben0` | SUCCEEDED | 3 | 07:00:01 → 아니오 | 07:20:00.000000 | — | 1 | 2 | 마이크로초까지 같으면 동률 |
| G1 | `cal0` | SUCCEEDED | 3 | 07:00:01 → 아니오 | 07:20:00.000001 | — | 3 | 3 | 1µs 늦음 · 경쟁 순위 건너뜀 |
| G1 | `dan0` | SUCCEEDED | 3 | 07:00:01 → 아니오 | 07:20:00.000001 | 3 | 4 | 4 | 3순위 패스 쓴 쪽 아래 |
| G1 | `eve0` | SUCCEEDED | 3 | 07:00:01 → 아니오 | 07:20:00.000001 | 1 | 5 | 5 | 둘 다 패스면 큰 번호(3) 위 |
| G1 | `fay0` | SUCCEEDED | 3 | 07:00:01 → 아니오 | 07:20:00.000001 | 1 | 5 | 6 | 같은 번호 패스 동률 |
| G1 | `gus0` | FAILED | 2 | 07:00:01 → 아니오 | 07:05:00.000000 | 1 | 7 | 7 | 진행도 2 < 3 · 시각이 패스보다 먼저 |
| G1 | `hal0` | FAILED | 2 | 07:00:02 → 아니오 | 08:00:01.500000 (유예) | — | 8 | 8 | 유예 도착도 그대로 비교 |
| G1 | `ivy0` | FAILED | 1 | 07:00:01 → 아니오 | 07:01:00.000000 | 2 | 9 | 9 | 진행도 1 |
| G1 | `jon0` | CHEAT_FAILED | 2 | 07:00:01 → 아니오 | 07:02:00.000000 | — | 10 | 10 | 0순위 — 진행도가 커도 맨 아래 |
| G2 | `ann1` | FAILED | 1 | 07:10:00 → 예 | 07:30:00.000000 | — | 1 | 1 | 진행도 1 > 0 (지각 무관) |
| G2 | `bo11` | FAILED | 0 | 07:00:00.500000 → 아니오 | — | — | 2 | 2 | 2′ · 3′ 모두 같음 → 동률 |
| G2 | `bo_1` | FAILED | 0 | 07:00:03.000000 → 아니오 | — | — | 2 | 3 | 07:00:03 정각은 지각 아님 · 표시는 바이트 순(`1` < `_`) |
| G2 | `cy_1` | FAILED | 0 | 07:00:01 → 아니오 | — | 1 | 4 | 4 | 3′ 패스 쓴 쪽 아래 |
| G2 | `di_1` | FAILED | 0 | 07:00:02 → 아니오 | — | 1 | 4 | 5 | 진행도 0 둘 다 패스 → 동률 |
| G2 | `ed_1` | FAILED | 0 | 07:00:03.000001 → 예 | — | — | 6 | 6 | 2′ 지각(1µs 넘김)은 패스보다 먼저 봄 |
| G2 | `gil1` | FAILED | 0 | 07:30:00 → 예 | — | 1 | 7 | 7 | 지각 + 패스 |
| G2 | `hu_1` | FAILED | -1 | — | — | — | 8 | 8 | 진행도 -1(미접속) 동률 |
| G2 | `ike1` | FAILED | -1 | — | — | — | 8 | 9 | 같음 |
| G2 | `jay1` | CHEAT_FAILED | 0 | 07:00:01 → 아니오 | — | — | 10 | 10 | 부정 실패는 미접속보다 아래 |
| G3 | `kz_1` | FAILED | -1 | — | — | — | 1 | 1 | 미접속도 부정 실패보다 위 |
| G3 | `kx_1` | CHEAT_FAILED | 2 | 07:00:01 → 아니오 | 07:40:00.000000 | — | 2 | 2 | 부정 실패끼리는 진행도와 무관하게 동률 |
| G3 | `ky_1` | CHEAT_FAILED | 0 | 07:00:01 → 아니오 | — | 1 | 2 | 3 | 같음 |

### 1.2 08:00 1차 · 08:00:03 2차 시나리오
Lua `deadline` 을 직접 부른다. `D` = 테스트가 정한 `deadlineUs`, `G` = `D + 3,000,000`. 「1차 시각」은 실행 순간의 Redis `TIME` 이 `D ≤ t < G` 가 되도록 `D = TIME − 0.5초` 로 잡고, 「2차」는 `D = TIME − 3.5초` 로 잡는다. 유예 풀이는 solve-signal 의 Lua `solve` 를 부르거나(있으면) 그 결과와 같은 필드를 직접 쓴다.

| id | 준비(그룹 Hash) | 실행 | 기대 |
|---|---|---|---|
| S1 | `a` IN_PROGRESS 진행도 1(`cur` 2번) · `b` NOT_STARTED -1 · `c` SUCCEEDED 3 | 1차 | `DECIDED` · `a` · `b` → `FAILED` · `c` 그대로 · `result FAILED_DEADLINE` · `rankingFinal 0` · 랭킹 `c 1 · a 2 · b 3` |
| S2 | G1(§1.1)의 1차 전 상태: `amy0`~`fay0` SUCCEEDED · `gus0` · `ivy0` IN_PROGRESS(`cur` 없음) · `hal0` IN_PROGRESS 진행도 1 · `cur` 2번 · `jon0` CHEAT_FAILED (나머지 키는 §1.1 과 같음) | 1차 → `hal0` 유예 풀이(도착 08:00:01.5 · 클릭 07:59:59.9) → 2차 | 1차 `DECIDED`(`hal0` 은 3번 진입이 아니므로 미루지 않음) · `hal0` 진행도 2 · `result` 그대로 · 2차 `FINALIZED` · 랭킹 = §1.1 G1 |
| S3 | `a` IN_PROGRESS 진행도 2 · `cur` 3번 · `b` SUCCEEDED 3 | 1차 | `PENDING` · `pending 1` · `result` 없음 |
| S4 | S3 뒤 `a` 의 3번 유예 풀이 → 전원 SUCCEEDED(solve-signal 이 `result SUCCEEDED` 확정) | 2차 | `FINALIZED` · `result SUCCEEDED` · `rankingFinal 1` · `pending` 없음 |
| S5 | S3 뒤 유예 풀이 없음 | 2차 | `DECIDED_FINAL` · `a` → `FAILED` · `result FAILED_DEADLINE` · `rankingFinal 1` · 이벤트 `group.decided` 1건만 |
| S6 | `a` IN_PROGRESS 진행도 2 · `cur` 없음(3번 진입 안 함) | 1차 | `DECIDED` — 미루지 않음 |
| S7 | `a` CHEAT_FAILED 진행도 2 · `cur` 3번 · `b` IN_PROGRESS 1 | 1차 | `DECIDED` — 부정 실패는 미룸 대상 아님 · `a` 는 CHEAT_FAILED 그대로 |
| S8 | S3 과 같음 | `phase=1` 을 `t ≥ G` 로(`D = TIME − 4초`) | `DECIDED_FINAL` — 한 번에 확정 · 랭킹 확정 |
| S9 | S3 과 같음 · `deadline` 실행 안 함 | API 조회(08:00:01) | `PENDING`(O19) |
| S10 | S1 과 같음 | `D = TIME + 10초` 로 1차 · `G = TIME + 1초` 로 2차(`D = TIME − 1초`) | 둘 다 `EARLY` · 변화 없음 |
| S11 | S1 · S3 | 같은 단계 3번 | O13 |
| S12 | 07:40 에 전원 SUCCEEDED(`result SUCCEEDED` · `rankingFinal` 1 또는 0) | 1차 → 2차 | 1차 `SKIPPED` · 2차 O14 |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 결과는 그룹 · 날짜마다 한 번(NFR-2) | O16 의 5,000 그룹을 1차 · 2차 동시에 + 같은 그룹에 유예 `solve` 를 겹쳐 실행(코루틴 64개) | 그룹마다 `ev` 의 `group.decided` ≤ 1 · `result` 값이 처음 쓴 값과 같다 |
| I2 | `rankingFinal` 은 0 → 1 로만, 1 뒤 랭킹 불변 | I1 실행 뒤 모든 그룹에 2차를 다시 · 이후 유예 밖 `solve` 시도 | `rankingFinal` 이 1 → 0 인 그룹 0 · `ranking` 바이트 같음 |
| I3 | 확정 뒤 미완료 상태 없음 | I1 실행 뒤 `result` 있는 그룹 전부 | `NOT_STARTED` · `IN_PROGRESS` 그룹원 0 |
| I4 | `pending` 과 `result` 는 함께 있지 않다 | I1 실행 중 1ms 마다 표본 100개 `HMGET` | `pending=1` 이고 `result` 있음 0건(Lua 원자성) |
| I5 | `version` 은 늘기만 하고 채널 메시지마다 다르다 | I1 중 그룹 채널 구독 | 그룹마다 받은 메시지 `version` 이 엄격히 증가 · 중복 0 |
| I6 | Lua 랭킹 = Kotlin 랭킹 | O3 | 불일치 0 |
| I7 | 경쟁 순위 성질 | O3 의 모든 그룹 | 각 줄 `rank = 1 + (나보다 엄격히 위인 줄 수)` · 동률 줄끼리 `rank` 같음 |
| I8 | 결과 캐시 날짜는 줄지 않는다 | O28 + 이벤트 1,000건을 무작위 순서 · 중복으로 persister 에 | 캐시의 `date` 가 처리 중 한 번도 줄지 않음 · 끝 값 = 가장 늦은 날짜의 확정 결과 |

## 3. 판정 밖
- 운영 규모(125만 그룹)의 1차 · 2차 완료 시간 — 로컬 Redis 하나로는 잴 수 없다. O17(1만 그룹 ≤ 5초)로 갈음하고, 운영 목표는 버킷 수 · 인스턴스 수로 늘린다(HLD platform §9).
- 실제 시계로 08:00 · 08:00:03 을 기다리는 종단 시험 — 두 경계를 ARGV 로 받으므로 단위로 판정하고, 앱 전체 시각 주입은 platform 의 시계 오프셋이 생기면 더한다.
- 유예 풀이 신호의 시간 창 판정(D1)과 거부 응답 — solve-signal ORACLE.
- SSE 프레임 형식 · 보는 사람별 표시 — realtime-board ORACLE. 이 문서는 채널 메시지 수 · `version` 만 본다(O5 · O6 · I5).
- Redis Cluster 의 슬롯 배치 — 키 이름에 같은 해시태그가 붙는지만 LLD 로 확인한다.
