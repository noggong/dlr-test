# ORACLE — game-session

> 판 v5.6 · 2026-11-11 (v5.6: T-GS-7 — O25 의 「WARN 한 줄 JSON」은 WARN 로그 줄의 메시지가 그 JSON 이다(줄 끝 일치). O25 · O26 · I7 의 worker 테스트는 날짜 `D` 를 KST 오늘 + 13 · 14일, 로그인 아이디를 무작위로 고른다(기동 때 파티션 · 다른 테스트와 겹치지 않게) — 기대 JSON 은 그 값으로 만든다. I7 의 Stream · persister 쪽은 worker `GameSessionEventInvariantTest` 가 실제 Lua 를 동시에 부른 뒤 전수 비교한다(persister 가 ACK 한 메시지를 트림하므로 소비 루프를 끄고 처리기를 Stream 순서대로 부른다)) (v5.5: 통합 — T-GS-4 판(v5.3)과 T-GS-8 판(브랜치 표기 v5.3 · v5.4 → v5.3b · v5.4b)을 합침, 내용 충돌 없음) (v5.4b: 부하 명령은 `-Dgame.load=true` 면 worker 테스트를 `*LoadTest` 로만 좁히고 결과 캐시 · up-to-date 를 쓰지 않는다 — 다른 컨텍스트의 PostgreSQL 호출이 섞이거나 측정 없이 성공으로 끝나지 않게. 통과 조건에 「건너뛴 테스트 0」. O34 · O35 는 측정 뒤 알려진 쿼리 1개로 집계가 살아 있는지 확인한다. O35 의 「1건당」은 단독 1건 + 1,000건 평균으로 본다) (v5.3b: 부하 · 메모리(O32 ~ O35)의 환경은 compose 가 아니라 worker 테스트의 Testcontainers — PostgreSQL 은 `pg_stat_statements` 를 미리 올려 띄운다. 문제은행 · 게임 정보는 HTTP 를 거치지 않고 `GameService` 를 직접 부른다(PostgreSQL 호출 수는 서비스 아래에서 나므로 같다 — 「`200`」은 예외 없이 은행을 받은 것). O32 의 문제 1,000개는 DB 가 아니라 메모리의 ID 목록) (v5.3: T-GS-4 리뷰 — O22 에 ④ 패스로 넘긴 문제의 늦은 진입 신호 · 그 뒤 교체 문제 진입 `200`) (v5.2: 통합 — T-GS-5 판(v4.1)과 T-GS-3 판(v5 · v5.1)을 합침, 내용 충돌 없음) (v5.1: §3 에 API 테스트의 시각 — 오프셋은 컨텍스트마다 하나라 기준의 입력 시각은 창 안 · 창 밖의 같은 쪽 시각으로 대신한다) (v5: O15 · O29 은행 키 만료는 남은 시간 = 그날 08:00 − 게임 시각 ±1초 — 은행도 앱 게임 시각으로 계산한 남은 ms 로 `PX`(LLD v6 §2.1). 고정 데이터의 「난이도마다 30개」는 시드가 120개라 테스트가 출제 이력 수로 맞춘다) (v4.1: `worker: member` 테스트(O27 · O28 · O30 · I4)는 `D` 를 KST 오늘 + 7일로 둔다 — O30 은 Redis 실제 시계로 만료를 보므로 앱 시계가 실제 시각이어야 하고, 그러면 `D+1 08:00` 이 지금보다 뒤여야 하며, 다른 테스트가 쓰는 오늘 · 내일 파티션을 건드리지 않고 `D+1` 파티션 생성을 보려면 그날이 비어 있어야 한다) (v4: O30 만료는 ±1초 — `roster` 가 앱 시각으로 계산한 남은 ms 로 `PEXPIRE` 한다(LLD v5 §4.1)) (v3: 테스트 이름 규약에 「(바탕)」 — 기준을 받치는 단위 테스트를 판정 테스트와 가른다) (v2: 통합 — `question.shown` 필드 이름을 HLD platform §4.2 로(`no` · `pk` → `questionNo` · `questionId`) · `app:worker` 모듈 뼈대는 platform 일감) · 짝 문서: [HLD](../hld/game-session.md) · [LLD](../lld/game-session.md) · [SPEC](../spec/game-session.md) · [ORACLE](../oracle/game-session.md) · 근거: [PRD](../prd.md) v10 FR-1 · FR-3 · §5.2 · D6 · D8 · D10 · D14 · D15 · D18

**세 줄 요약**
- 기준 35개(O1 ~ O35 — 엔드포인트 3개의 정상 · 오류 코드 전부, `worker: member` · `worker: prepare-questions` · persister 핸들러, 부하 · 메모리 4개) + 동시성 · 불변식 7개(I1 ~ I7).
- 확인 명령은 Gradle 테스트 6개(모두 이 기능의 일감이 세운다 — 패키지 `gamesession`). Redis · PostgreSQL 은 Testcontainers(`redis:8-alpine` · `postgres:18-alpine` + LLD platform §2.1 DDL)로 띄우고, 시각은 `game.clock-offset`(LLD §1 `GameProperties.clockOffset`)으로 옮긴다.
- 판정 밖: 07:00 직전 가입 트랜잭션의 ms 창 · Redis `TIME` 마이크로초 경계의 정확한 재현 · 운영 규모(250만) 지연 · 클라이언트의 문제 고르기.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.gamesession.*'` | `GameClock`(GS1 · 경계 시각) · `QuestionBankBuilder` · `DayRoster` 단위 | JDK 21. 이 기능의 일감이 세운다 |
| `cd backend && ./gradlew :infra:redis:test --tests 'com.delightroom.mission.infra.redis.gamesession.*'` | Lua `roster` · `start` · `shown` · 은행 캐시 | Docker(Testcontainers `redis:8-alpine`). 이 기능의 일감이 세운다 |
| `cd backend && ./gradlew :infra:rdb:test --tests 'com.delightroom.mission.infra.rdb.gamesession.*'` | LLD §6 Q1 ~ Q12 | Docker(Testcontainers `postgres:18-alpine` + LLD platform DDL). 이 기능의 일감이 세운다 |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.gamesession.*'` | 엔드포인트 3개 끝에서 끝(WebTestClient + Redis · PostgreSQL 컨테이너 · `clockOffset`) | Docker. 이 기능의 일감이 세운다 |
| `cd backend && ./gradlew :app:worker:test --tests 'com.delightroom.mission.app.worker.gamesession.*'` | `worker: member` · `worker: prepare-questions` · persister 핸들러 | Docker. 이 기능의 일감이 세운다(`app:worker` 모듈 뼈대 · persister 소비 루프는 platform 일감이 먼저 만든다) |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.RouteSmokeTest'` | 컨트롤러 라우트 = `openapi.yaml` 오퍼레이션(진입 신호 포함) | 있다 |
| `cd backend && ./gradlew :app:worker:test --tests 'com.delightroom.mission.app.worker.gamesession.*LoadTest' -Dgame.load=true` | 부하 · 메모리(O32 ~ O35). 속성이 없으면 건너뛴다 | Docker(worker 의 Testcontainers — PostgreSQL 은 `shared_preload_libraries=pg_stat_statements`). 속성이 있으면 worker 빌드가 테스트를 `*LoadTest` 로만 좁혀(같은 JVM 의 다른 테스트 컨텍스트가 PostgreSQL 을 부르지 않게) 캐시 없이 매번 돈다. 통과 조건에 테스트 리포트의 건너뜀 0 이 더해진다. 이 기능의 일감이 세운다 |

**테스트 파일 규약**: 패키지 `…<모듈>.gamesession`, 파일 `<대상>Test.kt`(부하는 `<대상>LoadTest.kt`), 테스트 이름은 `O<n>` · `I<n>` 으로 시작한다(예: `` `O12 한 난이도라도 미출제 0개면 QUESTIONS_EXHAUSTED` ``) — 이 표와 1:1 로 찾을 수 있게. 기준을 판정하지 않고 그 기준이 기대는 단위(문제 캐시 · 경계 시각 · 설정 검사 등)만 보는 테스트는 `O<n> (바탕)` 으로 시작한다 — 그 기준의 통과 근거로 세지 않는다.

**공통 고정 데이터**(테스트 픽스처): 게임 날짜 `D = 2026-11-12`. 문제 난이도마다 30개. 그룹 A(06:00 가입 3명 `alice_a` · `bob_b` · `carol_c` + 07:30 가입 `late_d`) · 그룹 S(06:00 가입 1명 `solo_s`) · 그룹 Z(07:10 가입 1명 `zero_z` — 그날 그룹원 0명).

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | 07:00 전 게임 정보는 지금까지의 07:00 전 가입자 | SPEC §2.1 · GS1 · GS3 · LLD §3.1-3 | `clockOffset` 으로 서버 시각 `D 06:55` → `GET /games/today`(`alice_a`) | `200`, `date = D`, `startAt = D 07:00:00+09:00`, `deadlineAt = D 08:00:00+09:00`, `members` = `[alice_a, bob_b, carol_c]`(이 순서), `serverTime` 과 실제 오프셋 시각 차 ≤ 1초, 명단 Hash 키 0개(만들지 않음) |
| O2 | 07:00 이후 게임 정보는 확정 명단 · 없으면 그 자리에서 만든다 | SPEC §2.1 · GS3 · LLD §3.4 | `D 07:40`, 명단 키 없음 → `GET`(`alice_a` · `late_d` 각각) | 둘 다 `200`, `members` = `[alice_a, bob_b, carol_c]`(`late_d` 없음), 명단 Hash `#size = 3` · `#name` = 그룹명 |
| O3 | 08:00 을 넘으면 다음 날 게임 | SPEC GS1 | `D 07:59:59.999` · `D 08:00:00.000` 에서 `GET` | 앞은 `date = D`, 뒤는 `date = D+1` · `startAt = D+1 07:00:00+09:00` |
| O4 | 인증 없으면 401 | SPEC §1 공통 · §2.1 ~ §2.3 오류 | 세 엔드포인트를 쿠키 없이 · 서명이 틀린 JWT 로 | 6번 모두 `401`, `code = UNAUTHORIZED`, Redis 키 수 변화 0 |
| O5 | 첫 문제은행 = 미션 시작 | SPEC §2.2 · GS5 · LLD §4.2 | `D 07:00:01` → `POST /question-bank`(`alice_a`) | `200`, `missionStartedAt` ∈ [`D 07:00:00`, `D 08:00:00`), 그룹 Hash `<uid>:p = 0` · `<uid>:st = IN_PROGRESS` · `<uid>:sa` = 응답 값(µs 같음), `version` 1 증가, `ev:{<b>}` 에 `mission.started` 1건 |
| O6 | 지각 판정(D8) | SPEC GS5 · LLD §4.2-6 | Lua `start` 를 `lateUs = TIME + 1초` · `lateUs = TIME − 1초` 로 각각 호출 · `GameClock` 단위 테스트로 `lateUs = D 07:00:03.000000` | 앞 `<uid>:late = 0`, 뒤 `= 1`, `GameClock.lateUs(D)` = `D 07:00:03+09:00` 의 epoch µs, Lua 비교식이 `>`(코드 리뷰 1줄) |
| O7 | 같은 날 재요청은 같은 시작 · 같은 은행 | SPEC GS5 · GS7 | O5 뒤 `POST /question-bank` 2번 더 | 3개 응답의 `missionStartedAt` 같음, `questions` 배열(순서 포함) 같음, `mission.started` 여전히 1건, `version` 변화 0 |
| O8 | 07:00 전 문제은행은 `GAME_NOT_OPEN` | SPEC §2.2 오류 · GS2 · D14 | `D 06:59:59` → `POST /question-bank` | `409 GAME_NOT_OPEN`, 그룹 Hash · 은행 키 · Stream 변화 0 |
| O9 | 08:00 이후 문제은행은 `SUBMISSION_CLOSED` | SPEC §2.2 오류 · GS2 | `D 08:00:00` · `D 08:00:02` → `POST /question-bank` | 둘 다 `409 SUBMISSION_CLOSED`(유예 없음) |
| O10 | 07:00 이후 가입자는 `NOT_PARTICIPANT` | SPEC §2.2 · §2.3 오류 · GS3 | `D 07:40` → `late_d` 가 문제은행 · 진입 신호 | 둘 다 `403 NOT_PARTICIPANT`, 그룹 Hash 에 `late_d` 필드 0개 |
| O11 | 1명 그룹은 `SOLO_GROUP` · 결과 상태를 만들지 않음 | SPEC §2.2 · §2.3 오류 · GS4 · LLD §4.1 | `D 07:10` → `solo_s` 가 문제은행 · 진입 신호 · 게임 정보 | 문제은행 · 진입 `409 SOLO_GROUP`, 게임 정보 `200` · `members` 1개, 그룹 S 의 그룹 Hash 키 없음, `day:{<b>}:<D>` 에 S 없음 |
| O12 | 미출제 0개 난이도가 있으면 `QUESTIONS_EXHAUSTED` | SPEC §2.2 오류 · GS6 · LLD §5 | `bob_b` 의 출제 이력에 난이도 3 문제 30개 전부 → `D 07:05` 문제은행 | `409 QUESTIONS_EXHAUSTED`, `bank:{<uid>}:<D>` 없음, 그룹 Hash `<uid>:p = 0`(시작은 반영 — GS5) |
| O13 | 은행 구성(D10) | SPEC GS6 · LLD §4.4 | `carol_c` 출제 이력: 난이도 1 의 15개 → 문제은행 | 난이도 1 = 15개(이력 밖 15개 전부), 2 · 3 = 20개씩, 이력과 겹침 0, 중복 0, 각 `difficulty` = 그 칸, `answerHash` = `questions.answer_hash`, JSON 에 `answer` 키 0개, 난이도 1 → 2 → 3 순서 |
| O14 | 미리 만든 은행이 있으면 그대로 · PostgreSQL 을 읽지 않음 | SPEC GS6 · D18 · LLD §3.2-3 | `bank:{<uid>}:<D>` 를 미리 넣고 문제은행 | 응답 `questionId` 들 = 키의 ID 들을 `pk_hash` 로 바꾼 것(순서 같음), LLD Q2 호출 수 0(리포지토리 스파이) |
| O15 | 은행이 없으면 그 자리에서 만들고 08:00 만료로 캐시 | SPEC GS6 · LLD §2.1 · §3.2-3 | 은행 키 없음 → 문제은행 | `200`, 키 생김, 남은 시간(`PTTL`) = `D 08:00:00+09:00` − 요청 때 게임 시각 ±1초(Redis 실제 시각이 아니라 `game.clock-offset` 을 더한 시각 기준) |
| O16 | 같은 날 재요청에 오늘 낸 문제가 그대로 있다 | SPEC GS8 | 문제은행 → 1번 문제 `q` 진입 신호 → 문제은행 | 두 번째 응답에 `q` 있음, 두 응답 목록 같음 |
| O17 | 진입 신호로 지금 문제 고정 | SPEC §2.3 · GS10 · LLD §4.3-6 | 시작한 `alice_a` 가 `{1, q}` | `200` `{questionNo:1, questionId:q, progress:0, missionStatus:"IN_PROGRESS"}`, 그룹 Hash `<uid>:cur = q`, `question.shown` 1건(`questionNo=1` · `questionId=q` · `qid` = `questions.id`), `version` 변화 0 |
| O18 | 진입 신호 멱등 | SPEC GS10 | O17 요청을 2번 더 | 3개 응답 같음, `question.shown` 여전히 1건 |
| O19 | 본문 검증 `INVALID_REQUEST` | SPEC §2.3 오류 · LLD §3.3-1 | `questionNo` 0 · 4 · `"1"`(문자열) · 없음, `questionId` 63자 · 대문자 hex · 없음, 본문 JSON 아님 — 8가지 | 8번 모두 `400 INVALID_REQUEST`, Lua 호출 0(Stream · Hash 변화 0) |
| O20 | 진입 신호 시간 창 | SPEC §2.3 오류 · GS2 | `D 06:59:59` · `D 08:00:00` 에 진입 신호 | 앞 `409 GAME_NOT_OPEN`, 뒤 `409 SUBMISSION_CLOSED` |
| O21 | 시작 전 · 완료 뒤 진입 신호 | SPEC §2.3 오류 · GS9 | 문제은행 전 `bob_b` 진입 / 그룹 Hash `<uid>:st` 를 `SUCCEEDED` · `CHEAT_FAILED` 로 둔 유저의 진입 | `409 MISSION_NOT_STARTED` / 두 경우 `409 MISSION_ALREADY_FINISHED` |
| O22 | `QUESTION_MISMATCH` 네 경우 | SPEC §2.3 오류 · GS9 · GS11 | ① 없는 PK(형식은 맞음) ② 난이도 2 문제로 `questionNo=1` ③ 1번에 `q` 고정 뒤 다른 난이도 1 문제 `r` ④ `q` 를 패스한 뒤 `{1, q}`(늦은 재시도) → 이어서 교체 문제 `r` | ① ~ ④ 모두 `409 QUESTION_MISMATCH`, ③ 뒤 `<uid>:cur = q` 그대로, ④ 뒤 `cur` 없음 · 이어진 `{1, r}` 은 `200` · `cur = r` |
| O23 | `QUESTION_OUT_OF_ORDER` | SPEC §2.3 오류 · GS9 · GS10 | 진행도 0 에서 `questionNo=2` / 진행도를 1 로 둔 뒤 `questionNo=1`(늦은 재시도) | 둘 다 `409 QUESTION_OUT_OF_ORDER` |
| O24 | 진입 신호 판정 순서 | SPEC GS9 | 시작 전 + 없는 PK → / 시작 뒤 + 없는 PK + `questionNo=3` → | 앞 `MISSION_NOT_STARTED`(MISMATCH 아님), 뒤 `QUESTION_MISMATCH`(OUT_OF_ORDER 아님) |
| O25 | 출제 이력 중복은 거부하지 않고 운영 로그 한 줄 | SPEC GS12 · HLD §4.1 · LLD §3.7 · Q12 | `served_questions` 에 (`alice_a`, `q`, `D-1`, 2) 행 → `D` 에 `{1, q}` 진입 → persister 처리 | 진입 `200`, persister stdout 에 WARN 로그 한 줄 — 메시지가 JSON `{"event":"question.served_duplicate","date":"2026-11-12","loginId":"alice_a","questionNo":1,"questionId":"<q>"}` 정확히 1줄, 기존 행 `game_date = D-1` 그대로 |
| O26 | persister 핸들러 멱등 | LLD §3.7 · Q11 · Q12 | `mission.started` · `question.shown` 을 각각 2번 처리 | `daily_progress`: `progress 0` · `IN_PROGRESS` · `started_at` = 이벤트 `startedAt` · `is_late` = 이벤트 `late`(행 1개). `served_questions`: `SHOWN` 행 1개. 운영 로그 0줄 |
| O27 | `worker: member` 명단 확정 | HLD §3 · LLD §3.5 · §4.1 | 고정 데이터로 잡 1번 | 명단 `#size`: A=3 · S=1 · Z=0. 그룹 Hash 는 A 만(`<uid>:p = -1` · `<uid>:st = NOT_STARTED` × 3, `version = 0`). `day:{<b>}:<D>` = {A}. `daily_progress(D)` -1 행 3개. `D+1` 파티션 4개(`pg_class` 로 셈) |
| O28 | `worker: member` 다시 돌리기 · `api` 가 먼저 만든 경우 | LLD §3.5 · §4.1-1 | `api` 가 A 명단을 만들고 `alice_a` 시작 → 잡 2번 | `alice_a` `<uid>:p = 0` 그대로, `version` 그대로, `daily_progress(D)` A 행 3개(늘지 않음), 잡 두 번째 실행의 Redis 쓰기 결과 `created = 0` × 3 그룹 |
| O29 | `worker: prepare-questions` 대상 · NX · 고갈 · 만료 | SPEC GS6 · D18 · LLD §3.6 | 가입 06:00 · 06:40 · 06:55 유저 + 고갈 유저 1명 + 06:00 유저 은행을 미리 넣음 → 06:30 실행 → 06:50 실행 | 06:30 뒤: 06:00 유저만(1명 그룹 `solo_s` 포함) 키 있음 · 미리 넣은 값 그대로. 06:50 뒤: 06:40 유저 추가. 06:55 유저 · 고갈 유저 키 없음. 모든 키 남은 시간 = `D 08:00` − 실행 때 게임 시각 ±1초(LLD v6 §2.1) |
| O30 | 명단 · 그룹 키 만료 | LLD §2.1 | O27 뒤 `EXPIRETIME` | 명단 Hash · 그룹 Hash · `day:{<b>}:<D>` 모두 `D+1 08:00:00+09:00` 의 epoch 초 ±1(앱 · Redis 시계 차이) |
| O31 | 계약 파일과 라우트 일치 | LLD §1 `openapi.yaml` · SPEC §1 | `RouteSmokeTest` 의 「컨트롤러 라우트와 openapi 오퍼레이션이 일치한다」 | 통과 · `openapi.yaml` 에 `POST /api/v1/games/today/shown` 과 `NOT_PARTICIPANT` · `QUESTIONS_EXHAUSTED` · `QUESTION_MISMATCH` 가 있다 |
| O32 | 은행 키 메모리 | HLD §5 은행 준비 · LLD §2.3 | `*LoadTest`: 문제 난이도마다 1,000개 · 유저 10,000명 은행을 만들고 `MEMORY USAGE` | 키 1개 평균 ≤ 1,024 B(× 250만 ≤ 2.5 GB — HLD 추정 범위) |
| O33 | `worker: prepare-questions` 처리량 | D18 · HLD §5 · LLD §3.6 | `*LoadTest`: 유저 100,000명 · 유저당 출제 이력 30개 · 06:30 실행 | 요약 줄의 초당 유저 ≥ 2,100(250만 / 20분 = 2,083) |
| O34 | 07:00 문제은행은 PostgreSQL 을 거치지 않는다 | HLD §1 목표 · D18 · LLD §3 공통 | `*LoadTest`: 명단 · 은행을 미리 만든 유저 10,000명이 문제은행 동시 요청(동시 200 — `GameService.startMission` 직접) | `pg_stat_statements` 의 전체 `calls` 증가 = 0(측정 쿼리 자신은 뺀다), 응답 10,000개 모두 `200`(예외 없이 문제 60개) |
| O35 | 07:00 전 게임 정보의 PostgreSQL 부하 | LLD §3.1-3 · §5 | `*LoadTest`: `D 06:59` 게임 정보 1,000건 | 요청 1건당 PostgreSQL 쿼리 ≤ 2(Q4 · Q6 — 단독 1건의 증가와 1,000건 평균으로 본다), 전체 `calls` 증가 ≤ 2,000 |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 미션 시작은 한 번 · 시작 시각 하나 | 같은 유저 문제은행 100건 동시(코루틴 100개) — 20회 반복 | 회마다 `mission.started` 정확히 1건, 100개 응답의 `missionStartedAt` 모두 같음 |
| I2 | 은행은 하나 | 은행 키 없는 유저의 문제은행 100건 동시 — 20회 반복 | 회마다 100개 응답의 `questions` 같음(순서 포함), 키 값 = 응답 |
| I3 | 번호마다 지금 문제 하나 | 같은 유저 · 같은 번호 · 서로 다른 PK 2개 진입 신호 동시 — 50회 반복 | 회마다 `200` 정확히 1개 · 나머지 `QUESTION_MISMATCH`, `<uid>:cur` = `200` 받은 PK, `question.shown` 1건 |
| I4 | 명단은 한 번 만든다 | `D 07:00` 에 `worker: member` + 같은 그룹 문제은행 50건(서로 다른 그룹원 · 재시도 포함) 동시 — 20회 반복 | 회마다 `roster` `created = 1` 정확히 1번, 응답 `500` 0건, 그룹 Hash 그룹원 필드 = 07:00 전 가입자 |
| I5 | 은행에 출제 이력이 없다 | O29 · O33 데이터의 모든 은행 키 ↔ 만든 시점의 `served_questions` | 교집합 0 |
| I6 | 진행도는 줄지 않고 시작은 -1 에서만 | 그룹 Hash `<uid>:p = 2` 인 유저가 문제은행 | `<uid>:p = 2` · `<uid>:sa` 그대로, `mission.started` 0건 |
| I7 | 반영과 이벤트는 함께 | I1 · I3 꼴(같은 유저 문제은행 20건 동시 · 같은 번호 다른 PK 진입 20건 동시, 그룹원 6명 중 4명 시작 · 3명 진입) 실행 뒤 전수 비교, 그 이벤트를 persister 처리기로 두 번씩 처리 | `<uid>:sa` 가 있는 그룹원 수 = `mission.started` 수, `cur` 를 고정한 횟수 = `question.shown` 수(차이 0). `daily_progress` 의 `started_at` 이 있는 행 = `mission.started` 의 uid, `served_questions` `SHOWN` 행 = `question.shown` 의 (uid, `qid`) |

## 3. 판정 밖
- **07:00 직전 가입 트랜잭션**: `joined_at` 이 07:00 전인데 커밋이 07:00 뒤인 ms 창의 유저가 명단에서 빠질 수 있다(LLD §5) — 재현이 타이밍에 달려 자동 판정에 넣지 않는다.
- **API 테스트의 입력 시각**: `game.clock-offset` 은 Spring 컨텍스트마다 하나라 API 테스트는 창 안(07:00:01 부터 흐름) · 07:00 전(06:58) · 08:00 부터의 세 컨텍스트로 돈다. O10 · O11 의 07:40 · 07:10 은 창 안의 다른 시각으로, O8 의 06:59:59 는 06:58 + 경과로, O9 의 08:00:00 은 08:00 + 기동 시간으로 대신한다 — 판정이 같은 쪽 시각이고, 경계 자체는 Lua 테스트가 Redis `TIME` 바로 위 · 아래로 본다.
- **마이크로초 경계 재현**: Redis `TIME` 은 테스트가 고정할 수 없어 07:00:00.000000 · 07:00:03.000000 · 08:00:00.000000 정각은 O6 · O8 · O9 처럼 ±1초와 `GameClock` 단위 테스트 · 비교식 리뷰로 대신한다.
- **운영 규모 지연**: 250만 동시 요청 · Redis Cluster · 응답 지연 p99 는 로컬 Testcontainers 로 대표되지 않는다 — O32 ~ O35 는 비율 · 호출 수로만 판정하고, 운영 측정은 platform ORACLE 몫이다. O33 은 막 넣은 10만 명(버퍼가 데워진 상태)으로 재므로 250만 명 운영보다 낙관적인 수치다.
- **클라이언트 몫**: 은행에서 무작위로 고르기 · 이미 낸 문제 빼기(R-B6) · 고갈 시 패스 버튼 잠금(D10)은 web-client 가 확인한다.
- **SSE 전달 형식**: `start` 의 `SPUBLISH` 메시지 형식 · 현황판 「미접속 → 진행 중」 표시는 realtime-board ORACLE 이 확인한다(여기서는 publish 1회만 — O5 에서 구독자 수신 1건을 보조로 본다).
- **진입 신호의 문제가 내 은행에 있었는지**: 보지 않는다(D15 — 거부하지 않음). 이력 중복만 O25 로 본다.
- **새 문제 반영**: 문제 캐시는 기동 시 한 번 적재 — 재기동 없이 새 문제가 들어가는지는 기준이 아니다.
