# ORACLE — solve-signal

> 판 v6 · 2026-11-11 (v6: T-PF-15 — O34 시각 범위 KST 경계 · v5: T-SS-3 리뷰 반영 — I1 api 경로는 version · Stream 을, 채널 메시지 건수는 Lua 경로 `SignalConcurrencyTest` 가 판정 · v4: T-SS-3 구현 반영 — api 테스트의 시각 배치(07:40 · 07:10 · 06:58 세 컨텍스트)와 O8 · O9 의 초 단위 경계는 `SignalServiceTest` 가 판정 · v3: T-SS-2 리뷰 반영 — O19 재시도 본문 비교에서 `groupStatus` 는 SS14 의 지금 값(② 는 `PENDING`) · v2: 통합 — 시각 주입 이름을 `game.clock-offset` · Lua ARGV `offsetUs` 로 · persister 소비 루프는 platform 일감) · 짝 문서: [HLD](../hld/solve-signal.md) · [LLD](../lld/solve-signal.md) · [SPEC](../spec/solve-signal.md) · [ORACLE](../oracle/solve-signal.md) · 근거: [PRD](../prd.md) v10 FR-4 · NFR-1 ~ NFR-3 · D1 · D9 · D17

**세 줄 요약**
- 기준 34개(O1 ~ O34) + 동시성 · 불변식 8개(I1 ~ I8) — 세 엔드포인트의 정상 · 모든 오류 코드, Lua 판정 순서, 그룹 결과 1회 기록, `worker: persister` 멱등 기록을 숫자로 판정한다.
- 확인 명령은 Gradle 테스트 4묶음(`infra:redis` Lua · `core` 서비스 · `app:api` HTTP · `app:worker` persister). 모두 **이 기능의 일감이 세운다** — 아직 없다.
- 판정 밖: 250만 규모 부하 · 실제 네트워크 지연에서 유예 3초가 충분한지 — 로컬로 잴 수 없다(§3).

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `cd backend && ./gradlew :infra:redis:test --tests 'com.delightroom.mission.infra.redis.signal.*'` | Lua `solve` · `pass` · `fail` · `decide` 단위 · 동시성 | JDK 21 · Docker(Testcontainers `redis:8-alpine`) |
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.service.signal.*'` | SignalService 의 `NO_ROSTER` · 거부 코드 변환 · 시간 창 계산 | JDK 21 |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.signal.*'` | HTTP 계약(상태 코드 · 본문 · 검증 · 인증) | JDK 21 · Docker(Redis) |
| `cd backend && ./gradlew :app:worker:test --tests 'com.delightroom.mission.app.worker.persister.Signal*'` | Stream → PostgreSQL 멱등 기록 | JDK 21 · Docker(`redis:8-alpine` · `postgres:18-alpine`) |
| `cd backend && ./gradlew test` | 전체(라우트 스모크 · 아키텍처 규칙 포함) | JDK 21 · Docker |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

**테스트 이름 규약** (이 기능의 일감이 세운다)
- 파일: `infra/redis/src/test/kotlin/…/infra/redis/signal/{SolveScriptTest,PassScriptTest,FailScriptTest,DecideScriptTest,SignalConcurrencyTest}.kt` · `core/domain/src/test/kotlin/…/core/service/signal/SignalServiceTest.kt` · `app/api/src/test/kotlin/…/app/api/signal/{SolveApiTest,PassApiTest,FailureApiTest}.kt` · `app/worker/src/test/kotlin/…/app/worker/persister/SignalPersisterTest.kt`.
- 테스트 메서드 이름은 기준 id 로 시작한다: `` `O10 도착 08_00_02_9 클릭 07_59_59_9 는 반영` `` — `--tests '*O10*'` 로 하나만 돌릴 수 있다.
- 시각: Lua 테스트는 `ARGV offsetUs` 로 Redis `TIME` 을 목표 시각에 맞춘다(오프셋 = 목표 − 지금 `TIME`). API 테스트는 `game.clock-offset` 과 `MutableClock`(날짜)을 함께 옮긴다.
- 그룹 Hash 준비: 테스트 도우미 `GroupHashFixture` 가 LLD §2.1 칸을 직접 쓴다(game-session 구현 없이도 돈다). api 테스트는 `SignalApiTestSupport.group(…)` 이 같은 칸을 쓴다.
- api 테스트의 시각: `game.clock-offset` 은 컨텍스트를 만들 때 한 번 계산하고 그 뒤 실제 시간만큼 흐르므로 한 클래스가 한 시각이다 — `SolveApiTest` 07:40(O1 · O2 · O4 · O5 · O6 · I1) · `PassApiTest` 07:10(O7 · O8 명단 있음 · 패스 · 실패 `200`) · `FailureApiTest` 06:58(O8 명단 없음 · O9). O8 · O9 의 06:59 · 06:59:59 는 컨텍스트 기동 중 07:00 을 넘지 않게 06:58 에 두고, 초 단위 경계(Lua `t` 와 앱 시각이 07:00 을 사이에 두고 어긋나는 경우 포함)와 08:00 이후 명단 없음은 `SignalServiceTest` 가 가짜 시계로 판정한다.
- I1 의 두 길: api 경로(`SolveApiTest`)는 응답 100개 · `<uid>:p` · version · Stream 을, 「채널 메시지 1건」은 Lua 경로(`SignalConcurrencyTest`)가 판정한다(api 테스트는 채널을 구독하지 않는다).

## 1. 기준
`t` = Redis 도착 시각. 「Stream +n」 = 그 호출 전후 `XLEN ev:{<b>}` 차이. 「version +n」 = 그룹 Hash `version` 차이.

| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | 풀이 정상 반영 | SPEC SS1 · SS6 · LLD §3.1 | 진행도 0 · 지금 문제 = Q1 · `t` = 07:10 에 `solve(1, Q1)` | `200` `{progress:1, missionStatus:IN_PROGRESS, groupStatus:IN_PROGRESS}` · Hash `<uid>:p = 1` · `<uid>:ls = t` · `<uid>:cur` 없음 · version +1 · Stream +1(`type=solve.applied`) · 채널 메시지 1건 |
| O2 | 풀이 재시도는 같은 응답 · 무반영 | SS2 | O1 뒤 같은 본문 다시 | `200` 본문이 O1 과 바이트 단위로 같다 · version +0 · Stream +0 · 채널 메시지 0건 |
| O3 | 3번 반영 → 미션 성공 | SS6 | 진행도 2 · 지금 문제 Q3 · 다른 그룹원 `IN_PROGRESS` · `solve(3, Q3)` | `200` `{progress:3, missionStatus:SUCCEEDED, groupStatus:IN_PROGRESS}` · `<uid>:st = SUCCEEDED` · `result` 없음 |
| O4 | 본문 검증 | SPEC §2 · SS1 | `solves` 에 ① `questionNo` 0 ② 4 ③ `questionId` 63자 ④ 대문자 hex ⑤ `clickedAt` 오프셋 없음 `2026-11-12T07:00:00` ⑥ `successTime` 빠짐 · `passes` 에 ⑦ `questionNo` 빠짐 ⑧ `questionId` 65자 | 8건 모두 `400` `code=INVALID_REQUEST` · Redis 호출 0회(version +0) |
| O5 | 인증 | SPEC §1 | 세 엔드포인트를 ① 쿠키 없이 ② 만료 토큰 ③ 서명이 틀린 토큰으로 | 9건 모두 `401` `code=UNAUTHORIZED` |
| O6 | 그날 그룹원이 아님 | SS15 | 명단 Hash 에 없는 uid(07:30 가입) 로 세 엔드포인트 · `t` = 07:40 | 3건 모두 `403` `NOT_PARTICIPANT` |
| O7 | 1명 그룹 | SS15 | 명단 Hash `#size = 1` · 그룹 Hash 없음 · `t` = 07:10 · 세 엔드포인트 | 3건 모두 `409` `SOLO_GROUP` |
| O8 | 미션 시작 전 | SS1 · SS8 · SS11 · D14 | `<uid>:st = NOT_STARTED` · `t` = 07:10 · 세 엔드포인트 · 그리고 명단 없음 · `t` = 06:59 에 `failures` | 4건 모두 `409` `MISSION_NOT_STARTED` · version +0 |
| O9 | 07:00 전 | SS3 · SS15 | 명단 없음 · `t` = 06:59:59 에 `solves` · `passes` | 2건 모두 `409` `GAME_NOT_OPEN` |
| O10 | 부정 실패 뒤 새 번호 · 옛 번호 재시도 | SS1 ③ · SS2 | 진행도 1 · `CHEAT_FAILED` 에서 ① `solve(2, …)` ② `solve(1, Q1)` | ① `409` `MISSION_ALREADY_FINISHED` ② `200` `{progress:1, missionStatus:CHEAT_FAILED, …}` · 둘 다 version +0 |
| O11 | 유예 구간 · 클릭이 08:00 이후 | SS3 | `t` = 08:00:01.000 · `clickedAt` = 08:00:00.000 | `409` `SUBMISSION_CLOSED` · version +0 |
| O12 | 유예 구간 경계 — 도착 08:00:00.000 · 클릭 07:59:59.999 | SS3 | 오프셋으로 `t` = 08:00:00.000(+지연 ≤ 50 ms) | `200` 반영 · Stream 메시지 `grace = 1` |
| O13 | 08:00 확정 그룹의 유예 풀이 | SS4 · D17 | `result = FAILED_DEADLINE` · `rankingFinal = 0` · 내 상태 `FAILED` · 진행도 1 · 지금 문제 Q2 · `t` = 08:00:01 · 클릭 07:59:59 · `solve(2, Q2)` | `200` `{progress:2, missionStatus:FAILED, groupStatus:FAILED}` · `result` 그대로 · `ranking` 이 새 진행도로 다시 계산됨 · Stream +1(`solve.applied`만, `group.decided` 0) |
| O14 | 마감 뒤 재시도는 같은 응답 | SS2 · HLD §4 판정 순서 | 07:59:00 에 반영된 `solve(2, Q2)` 를 `t` = 08:00:05 에 다시 | `200` 이전과 같은 진행도 · 상태 · version +0 |
| O15 | 번호 건너뜀 | SS5 | 진행도 0 에서 `solve(2, …)` · 진행도 0 에서 `pass(2, …)` | 2건 모두 `409` `QUESTION_OUT_OF_ORDER` |
| O16 | 지금 문제 불일치 · 없음 | SS5 | ① 지금 문제 Q1 인데 `solve(1, Qx)` ② 지금 문제 없음 · `solve(1, Q1)` ③ `pass(1, Qx)` ④ 지금 문제 없음 · `pass(1, Q1)` | 4건 모두 `409` `QUESTION_MISMATCH` · version +0 |
| O17 | 랭킹 시각은 서버 도착 시각 | SS12 | `successTime` = 06:00(과거) · 2099년(미래)로 각각 반영 | `<uid>:ls` = Lua 가 돌려준 `t`(±0) · `successTime` 과 무관 · persister 뒤 `solve_signals.success_time` = 보낸 값 · `arrived_at` = `t` |
| O18 | 패스 정상 반영 | SS8 · LLD §3.2 | 진행도 1 · 지금 문제 Q2 · `t` = 07:20 · `pass(2, Q2)` | `200` `{passedAtQuestionNo:2, questionNo:2, progress:1}` · `<uid>:pn = 2` · `<uid>:pq = Q2` · `<uid>:cur` 없음 · Stream +1(`pass.used`) |
| O19 | 패스 재시도 | SS9 | O18 뒤 같은 본문을 ① 07:21 ② 08:00:05 에 | 2건 모두 `200` O18 과 같은 `passedAtQuestionNo` · `questionNo` · `progress` · `missionStatus` · version +0 · `groupStatus` 는 ① `IN_PROGRESS` ② `PENDING`(SS9 · SS14 의 지금 값) |
| O20 | 두 번째 패스 | SS9 · R-P5 | O18 뒤 진행도 2 · 지금 문제 Q3 · `pass(3, Q3)` | `409` `PASS_ALREADY_USED` · `<uid>:pn = 2` 그대로 |
| O21 | 패스는 유예 없음 | SS10 | 진행도 1 · 지금 문제 Q2 · `t` = 08:00:00.500 · `pass(2, Q2)` | `409` `SUBMISSION_CLOSED` |
| O22 | 완료 뒤 패스 | SS8 ③ | `SUCCEEDED` 에서 · `CHEAT_FAILED` 에서 각각 패스(패스 미사용) | 2건 모두 `409` `MISSION_ALREADY_FINISHED` |
| O23 | 부정 실패 반영 | SS11 ③ | 진행도 1 · `IN_PROGRESS` · `t` = 07:30 · `failures` | `200` `{progress:1, missionStatus:CHEAT_FAILED, groupStatus:IN_PROGRESS}` · `<uid>:cf = t` · `<uid>:p = 1` · Stream +1(`member.cheat_failed`) |
| O24 | 미션 성공 뒤 실패 신호 무시(D9) | SS11 ② | `SUCCEEDED` · `failures` | `200` `missionStatus:SUCCEEDED` · version +0 · Stream +0 |
| O25 | 실패 신호 재시도 | SS11 ② | O23 뒤 다시 | `200` O23 과 같은 본문 · version +0 |
| O26 | 08:00 이후 · 결과 확정 뒤 실패 신호 무시 | SS11 ② · D17 | ① `IN_PROGRESS` · 결과 없음 · `t` = 08:00:00.100 ② `FAILED` · `result` 있음 · `t` = 08:00:10 | ① `200` `{missionStatus:IN_PROGRESS, groupStatus:PENDING}` ② `200` `{missionStatus:FAILED, groupStatus:FAILED}` · 둘 다 version +0 |
| O27 | 전원 성공 → 그룹 성공 1회 | SS7 · LLD §3.3a | 3명 그룹, 두 명 `SUCCEEDED` · 마지막 한 명이 `t` = 07:40 에 3번 반영 | 응답 `groupStatus:SUCCEEDED` · `result = SUCCEEDED` · `decidedAt = t` · `rankingFinal = 1` · Stream +2(`solve.applied` · `group.decided` 각 1) · 채널 메시지 1건 |
| O28 | 부정 실패가 섞인 전원 완료 → 즉시 실패 | SS7 · R-F6 | ① 2명 그룹, 한 명 `CHEAT_FAILED` · 다른 한 명이 3번 반영 ② 2명 그룹, 한 명 `CHEAT_FAILED` · 다른 한 명 `failures` | 두 경우 모두 `result = FAILED_IMMEDIATE` · 응답 `groupStatus:FAILED` · `group.decided` 1건 |
| O29 | 08:00 이후 결과 없는 그룹은 `PENDING` | SS14 | 미룬 그룹(`pending = 1`)에서 유예 풀이로 2번 반영(아직 미완료) | `200` `groupStatus:PENDING` |
| O30 | persister — 풀이 기록 멱등 | LLD §2.3 · §3.6 | `solve.applied` 같은 메시지를 2번 처리 | `solve_signals` 1행 · `daily_progress.progress` = 메시지 `progress` · `last_solved_at = arrivedAt` · `served_questions.kind = SOLVED` · 두 번째 처리 뒤 행 값 변화 0 |
| O31 | persister — 패스 · 부정 실패 기록 멱등 | LLD §2.3 | `pass.used` · `member.cheat_failed` 를 각 2번 처리 | `served_questions.kind = PASSED` 1행 · `daily_progress.pass_question_no` = 번호 · `status = CHEAT_FAILED` · `cheat_failed_at = at` · 두 번째 처리 뒤 변화 0 |
| O32 | persister — 그룹 결과 1회 기록 | LLD §2.3 · NFR-2 | `group.decided` 를 2번 처리 | `group_results` 1행 · `group_result_members` 행 수 = `memberCount` · `result:{<gid>}` 캐시 있음(TTL 89,000 ~ 90,000 초) |
| O33 | persister — ACK · 재처리 | LLD §3.6 | ① 정상 처리 뒤 `XPENDING` ② 처리 중 DB 연결을 끊었다 되살리고 30초 뒤 | ① pending 0 ② 같은 메시지가 다시 처리돼 O30 과 같은 행 · pending 0 |
| O34 | 시각 범위는 KST 기준 | SPEC §2 · api-guide §1 | `successTime` · `clickedAt` 각각 ① `2100-01-01T00:00:00+09:00` · `1999-12-31T23:59:59+09:00` ② `2000-01-01T00:00:00+09:00` · `2099-12-31T23:59:59+09:00` · `2000-01-01T00:00:00Z` | ① `400 INVALID_REQUEST` · version +0 · Stream +0 · 진행도 그대로 ② `200`(같은 시점이면 오프셋과 무관) — `SolveApiTest` O34 |

**엔드포인트 × 코드 대응**: `solves` — 200(O1 · O2 · O3 · O10② · O12 · O13 · O14 · O27) · 400(O4 · O34) · 401(O5) · 403(O6) · `SOLO_GROUP`(O7) · `MISSION_NOT_STARTED`(O8) · `GAME_NOT_OPEN`(O9) · `MISSION_ALREADY_FINISHED`(O10①) · `SUBMISSION_CLOSED`(O11 · I4) · `QUESTION_OUT_OF_ORDER`(O15) · `QUESTION_MISMATCH`(O16). `passes` — 200(O18 · O19) · 400(O4) · 401(O5) · 403(O6) · `SOLO_GROUP`(O7) · `MISSION_NOT_STARTED`(O8) · `GAME_NOT_OPEN`(O9) · `PASS_ALREADY_USED`(O20) · `SUBMISSION_CLOSED`(O21) · `MISSION_ALREADY_FINISHED`(O22) · `QUESTION_OUT_OF_ORDER`(O15) · `QUESTION_MISMATCH`(O16). `failures` — 200(O23 ~ O26 · O28②) · 401(O5) · 403(O6) · `SOLO_GROUP`(O7) · `MISSION_NOT_STARTED`(O8).

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | **같은 풀이 신호 100건 동시 → 1회 반영** (NFR-3 · SS2) | 진행도 0 · 지금 문제 Q1 에서 같은 `solve(1, Q1)` 100건을 코루틴 100개로 동시에(`api` 경유 — `SolveApiTest`, Lua 직접 — `SignalConcurrencyTest` 두 길 모두) | 100건 모두 `200` · 본문 100개가 모두 같다(`progress:1`) · `<uid>:p = 1` · version +1 · Stream `solve.applied` +1 · 채널 메시지 1건 |
| I2 | **그룹 마지막 두 명 동시 완료 → 결과 1회 기록** (NFR-2 · SS7) | 2명 그룹 · 둘 다 진행도 2 · 지금 문제 Q3 · 두 `solve(3, Q3)` 를 동시에. 그룹 Hash 를 새로 만들어 200회 반복 | 200회 모두: `result = SUCCEEDED` · Stream `group.decided` 정확히 1건 · 두 응답 중 적어도 하나 `groupStatus:SUCCEEDED` · 두 번째로 실행된 쪽 응답은 `SUCCEEDED` · 두 유저 `SUCCEEDED` |
| I3 | **도착 08:00:02.9 · 클릭 07:59:59.9 → 반영** (D1 · SS3) | 진행도 2 · 지금 문제 Q3 · 오프셋으로 `t` = 08:00:02.900 · `clickedAt = 07:59:59.900+09:00` · 20회 반복 | 20회 모두 `200` · `<uid>:p = 3` · Lua 가 돌려준 `t` 가 08:00:02.900 ~ 08:00:02.999 · Stream `grace = 1` |
| I4 | **도착 08:00:03.0 → 거부** (D1 · NFR-1) | 같은 준비 · `t` = 08:00:03.000 · `clickedAt = 07:59:59.900+09:00` · 20회 반복 | 20회 모두 `409` `SUBMISSION_CLOSED` · `<uid>:p = 2` 그대로 · version +0 · Stream +0 |
| I5 | 진행도는 줄지 않고 완료 상태는 바뀌지 않는다 | 무작위 신호 열(풀이 · 패스 · 실패 · 재시도, 시각 06:59 ~ 08:00:05) 1,000개를 Lua 에 넣는 속성 테스트 | 모든 단계에서 `p` 비감소 · `SUCCEEDED` · `CHEAT_FAILED` 가 된 뒤 상태 변화 0 · `p` ∈ [-1, 3] |
| I6 | 반영 ⇔ 이벤트 · version | I5 의 같은 열 | 응답 `OK` 마다 version +1 · 그 종류 Stream 메시지 +1(결과를 쓴 호출은 `group.decided` +1 더). `REPLAY` · 거부마다 version +0 · Stream +0 |
| I7 | 같은 패스 100건 동시 → 1회 | 진행도 1 · 지금 문제 Q2 · `pass(2, Q2)` 100건 동시 | 100건 모두 `200` 같은 본문 · `pass.used` +1 |
| I8 | 부정 실패와 마지막 풀이가 동시에 와도 결과 1회 | 2명 그룹 · A 진행도 2 · 지금 문제 Q3 · B `IN_PROGRESS` · A `solve(3)` 와 B `failures` 동시, 200회 반복 | 200회 모두 `result = FAILED_IMMEDIATE` · `group.decided` 정확히 1건 |

## 3. 판정 밖
- 250만 동시 · 피크 2만 건/초 부하와 Lua 처리 지연 — 로컬 compose 단일 Redis 로는 운영 규모를 잴 수 없다(HLD platform §7 의 산술 추정만 둔다).
- 08:00 직후 몰림에서 LB · 큐잉 지연이 유예 3초를 넘는지(HLD §6 위험) — 운영 네트워크가 없다. I3 · I4 는 Redis 도착 시각 기준의 경계만 판정한다.
- Redis 프로세스 장애 때 최대 1초 유실(NFR-5 허용 범위) — 유실 자체는 설계상 허용이고, 재시도 멱등은 O2 · I1 이 본다.
- 랭킹 순서 자체(동률 · 경쟁 순위) — deadline-result ORACLE 이 판정한다. 여기서는 결과 확정 · 유예 반영 때 `ranking` 이 다시 쓰였는지만 본다(O13 · O27).
- SSE 로 그룹원 화면에 도착하는지 · 보는 사람별 표시(D2) — realtime-board ORACLE. 여기서는 채널 메시지 건수만 센다.
