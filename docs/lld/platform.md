# LLD — platform (DB 설계)

> 판 v5.22 · 2026-11-11 (v5.22: T-PF-13 리뷰 — §4.4 앞으로만 반영 · 키 유실 시 다시 심기 · 이동이 세션 키 TTL 을 줄임 · 데모의 D24 한계 · 함수 이름 `pauseUntil` · `first` · v5.21: T-PF-13 — §1 데모 시계 파일 · §4.4 로컬 데모 시계 · 시각 이동(prd D25) · v5.20: 통합 — T-GS-9(참가 자격 06:50 · prd D24) 판과 T-PF-12(기동 경합) 판을 합침, 내용 충돌 없음 · v5.19: T-PF-12 리뷰 — Q9 는 테이블마다 한 문장(문장마다 잠금)으로 — 4개를 한 트랜잭션에 묶으면 부모 `ACCESS EXCLUSIVE` 를 여러 개 쥐어 07:00 생성이 persister DML(`solve_signals` → `daily_progress` 등)과 순환할 수 있다 · v5.18b: T-PF-12 — 기동 경합(빈 DB 에서 `api` 의 schema.sql 과 `worker` 의 파티션 DDL 이 deadlock `40P01` · `api` 둘이면 카탈로그 중복 `23505`): §4.3 스키마 잠금(advisory lock — schema.sql 전체 · Q9 를 한 줄로) · compose `api` 헬스체크와 `worker` 의 `api` healthy 의존 · §1 · §4 · 구현 순서 8 · v5.18a: 명단 기준 문구를 `joined_at < D 06:50` 으로 — prd D24 — §2 COMMENT 세 줄 · §3 표 · §4.2 · v5.17: T-PF-11 리뷰 — §2.2 정규식 이스케이프의 전제 `standard_conforming_strings` · v5.16: T-PF-11 — 정답 정규화(prd D22): §1 `AnswerNormalizer` · §2.1 `answer_hash` COMMENT · §2.2 시드 SQL 이 같은 규칙 · 구현 순서 7 · v5.15: T-PF-10 리뷰 — `iac/local.sh` 셸 값 우선 · 분리된 HEAD 는 폴더 이름 · v5.14: T-PF-10 — §1 에 `iac/compose.yaml` · `iac/local.sh` 줄(워크트리 격리 — 이미지 태그 · 프로젝트 이름) · 구현 순서 6 · v5.13: T-PF-8 — 스켈레톤 `NotImplementedYet` · `ErrorCode.NOT_IMPLEMENTED` 삭제로 `ErrorCode` 22개 · 쓰이지 않던 도메인 자리표시(`GameDay` · `GamePhase` · `Group.rosterOn` · `MissionProgress.start` — 판정은 Lua 가 한다) 삭제 · `RouteSmokeTest` 의 501 검사를 ORACLE C1 로 바꿈 · v5.12: 통합 — T-PF-5(재적재) 판과 main(T-PF-6 dead-letter · T-PF-7 오류 응답) 판을 합침. main 이 §4.1 을 오류 응답에 먼저 써서 재적재를 §4.2 로, ORACLE 재적재 기준을 O22 ~ O26 으로 옮김 · ‖ T-PF-5 갈래: v5.11: T-PF-5 리뷰 — 마감 따라잡기는 `created` 가 아니라 「08:00 뒤 · 기록이 있는 모든 그룹」(멱등 · 페이지 재시도 · 따라잡기 실패에도 빠지지 않게) · 따라잡기 실패와 기록이 규칙 밖인 그룹은 실패로 세어 표시를 쓰지 않음 · 표시는 실행 시작에 쓰고 끝에서 다시 셈 · `roster.lua` 꼬리 값 검사 · 한계 셋(08:00 뒤 Redis · worker 함께 재시작 · api 경로 ms 창 · 미리 읽은 값) · v5.10: T-PF-5 구현 — `rankingFinal` 은 08:00 전 조기 확정이면 1(Lua `decide.run` 과 같게) · v5.9: T-PF-5 — 재적재(rehydrate) 절차 §4.1(발동 = 명단을 만드는 두 길 + `worker: member` 버킷 표시 감시 · 칸 매핑 표 · 재적재 판정 · `version` = 재적재 시각 epoch ms · 동시성 · 마감 따라잡기 · persister 가 못 쓴 메시지 · 되살리지 못하는 것) · 쿼리 R1 · R2 · §1 파일 · §5 4단계 ORACLE O19 ~ O23 · v5.8: T-DR-6 리뷰 — 확정 랭킹 캐시 쓰기 실패는 키를 지우고, 못 지우면 재처리 · v5.7: T-DR-6 — 랭킹 확정(`group.ranking_final`)은 `group_results` 가 없으면 넣고 마감 실패 그룹원 `daily_progress FAILED` 도 같은 트랜잭션에서 · 결과 캐시 Lua 는 읽지 못하는 값을 덮는다 · v5.6: `worker` 는 `spring.main.keep-alive=true` — 웹 서버가 없고 루프 스레드가 데몬이라 그대로면 기동 직후 프로세스가 끝난다(로컬 기동에서 발견) · v5.5: T-PF-3 리뷰 반영 — 처리기 없는 `type` 은 ACK 하지 않고 버킷을 멈춤(처리기가 붙기 전 이벤트를 잃지 않게) · Redis 오류 뒤에도 자기 PEL 부터 · 처리기의 `Error` · 타임아웃도 처리기 실패로 · 연속 실패 10번부터 ERROR `persister.stuck` · `batch-size` ≤ 1,000 · 멈출 때 ACK 5초 제한 · 고정 index 운영 규칙 · v5.4: T-PF-3 구현 반영 — `app:worker` 뼈대 · persister 소비 루프 `StreamPersister` 의 절차(§3.1 — 버킷 나눔 · 레인 · 처리기 계약 · 실패 시 버킷 멈춤 · 자기 PEL 재읽기 · `XAUTOCLAIM` · ACK 와 트림) · 포트 `EventStreamConsumer` · Lua `persister_*` · 설정 `worker.persister.*` · v5.3: T-PF-1 리뷰 반영 — 기동 때 KST 오늘 · 내일 파티션(`worker: member` 전에 자정을 넘겨도 쓰기가 막히지 않게) · 시드가 이미 있으면 시퀀스를 쓰지 않음 · 시드 답의 문자 범위 · v5.2: T-PF-1 구현 반영 — `schema.sql` 전체를 `IF NOT EXISTS` 꼴로 · 하루 파티션은 날짜를 박지 않고 기동 때 KST 날짜로 만드는 `DO` 블록 · 문제 시드 `data.sql`(생성 규칙 · §2.2) · `daily_progress.status` 설명을 도메인 `board.MemberStatus` 로 · v5.1: 리뷰 반영 — lib 검사는 기동 때 · 반환의 nil/false · 환경 변수 이름 · `ErrorCode` 는 그대로 · v5: 기능들이 함께 쓰는 공통 코드 바탕 — `ErrorCode` · `GameProperties.clockOffset` · `bucketCount` · `RedisKeys` · `RedisScripts` · `lua/lib/common.lua` 와 lib 모듈 규약(§1 · §5 0단계 · 일감 T-PF-2) · v4: 마감 실패 그룹원 FAILED 기록 · v3: 통합 — 스키마 DDL 경로 `backend/infra/rdb/src/main/resources/schema.sql`(auth-signup LLD) · 결과 조회 인덱스 2개(deadline-result LLD §2.3) · `app:worker` 모듈 뼈대 · persister 소비 루프 `StreamPersister` 를 이 문서의 일감으로 · `group_result_members.cheat_failed` 설명을 API 계약(`status`)에 맞춤 · v2: prd v6 결정 D17(상태 FAILED · CHEAT_FAILED · 랭킹 확정) · prd v4 결정 D6 · D7 · D8 · D9 · D11 · D12 를 컬럼 설명에 · 로컬 실행 파일을 `iac/` 실제 경로로 · 상태 값을 도메인 · API 와 같게 · 결과 표시 상태를 prd D4 로 · 결과 스냅샷에 D5 필드 · 07:00 명단 확정 절차 ‖ main 갈래: v5.11: 통합 — T-PF-6(dead-letter · prd D20) 판과 T-PF-7(오류 응답 · prd D21) 판을 합침, 내용 충돌 없음 · v5.10: T-PF-6 리뷰 반영 — SQLSTATE 로 일시 장애 판별(08 · 40 · 53 · 57 · 58 · 25006) · 처리기가 다시 받기를 바라는 `RetryableEventException`(결과 캐시 확정 실패)은 세지 않음 · `event_type` TEXT · NUL 치환 · `last_error` 는 늦은 실패의 것 · dead-letter 하면 backoff 0 · v5.9a: T-PF-6 — persister dead-letter(prd D20): 한 메시지의 처리기가 `dead-letter-after`(3)번 실패하면(처리기 없는 `type` 포함 · 형식 오류는 처음에) `persister_dead_letters` 에 한 행을 쌓고 ACK 해 다음 메시지로 · DB · Redis 연결 장애는 세지 않음 · dead-letter 쓰기가 실패하면 ACK 안 함(§2.1 · §3.1 · 포트 `DeadLetterStore`) · v5.9b: T-PF-7 — 처리하지 못한 예외의 오류 응답(§4.1 — 503 `UNAVAILABLE` · 500 `INTERNAL_ERROR` 판별 · `WebExceptionHandler` · prd D21. 리뷰 반영: 5xx `ResponseStatusException` 도 500 · 클라이언트 끊김은 넘김 · Redis LOADING · READONLY · BUSY · 연결 상태 오류도 503) · `ErrorCode` 23개 · v5.8: T-DR-6 리뷰 — 확정 랭킹 캐시 쓰기 실패는 키를 지우고, 못 지우면 재처리 · v5.7: T-DR-6 — 랭킹 확정(`group.ranking_final`)은 `group_results` 가 없으면 넣고 마감 실패 그룹원 `daily_progress FAILED` 도 같은 트랜잭션에서 · 결과 캐시 Lua 는 읽지 못하는 값을 덮는다 · v5.6: `worker` 는 `spring.main.keep-alive=true` — 웹 서버가 없고 루프 스레드가 데몬이라 그대로면 기동 직후 프로세스가 끝난다(로컬 기동에서 발견) · v5.5: T-PF-3 리뷰 반영 — 처리기 없는 `type` 은 ACK 하지 않고 버킷을 멈춤(처리기가 붙기 전 이벤트를 잃지 않게) · Redis 오류 뒤에도 자기 PEL 부터 · 처리기의 `Error` · 타임아웃도 처리기 실패로 · 연속 실패 10번부터 ERROR `persister.stuck` · `batch-size` ≤ 1,000 · 멈출 때 ACK 5초 제한 · 고정 index 운영 규칙 · v5.4: T-PF-3 구현 반영 — `app:worker` 뼈대 · persister 소비 루프 `StreamPersister` 의 절차(§3.1 — 버킷 나눔 · 레인 · 처리기 계약 · 실패 시 버킷 멈춤 · 자기 PEL 재읽기 · `XAUTOCLAIM` · ACK 와 트림) · 포트 `EventStreamConsumer` · Lua `persister_*` · 설정 `worker.persister.*` · v5.3: T-PF-1 리뷰 반영 — 기동 때 KST 오늘 · 내일 파티션(`worker: member` 전에 자정을 넘겨도 쓰기가 막히지 않게) · 시드가 이미 있으면 시퀀스를 쓰지 않음 · 시드 답의 문자 범위 · v5.2: T-PF-1 구현 반영 — `schema.sql` 전체를 `IF NOT EXISTS` 꼴로 · 하루 파티션은 날짜를 박지 않고 기동 때 KST 날짜로 만드는 `DO` 블록 · 문제 시드 `data.sql`(생성 규칙 · §2.2) · `daily_progress.status` 설명을 도메인 `board.MemberStatus` 로 · v5.1: 리뷰 반영 — lib 검사는 기동 때 · 반환의 nil/false · 환경 변수 이름 · `ErrorCode` 는 그대로 · v5: 기능들이 함께 쓰는 공통 코드 바탕 — `ErrorCode` · `GameProperties.clockOffset` · `bucketCount` · `RedisKeys` · `RedisScripts` · `lua/lib/common.lua` 와 lib 모듈 규약(§1 · §5 0단계 · 일감 T-PF-2) · v4: 마감 실패 그룹원 FAILED 기록 · v3: 통합 — 스키마 DDL 경로 `backend/infra/rdb/src/main/resources/schema.sql`(auth-signup LLD) · 결과 조회 인덱스 2개(deadline-result LLD §2.3) · `app:worker` 모듈 뼈대 · persister 소비 루프 `StreamPersister` 를 이 문서의 일감으로 · `group_result_members.cheat_failed` 설명을 API 계약(`status`)에 맞춤 · v2: prd v6 결정 D17(상태 FAILED · CHEAT_FAILED · 랭킹 확정) · prd v4 결정 D6 · D7 · D8 · D9 · D11 · D12 를 컬럼 설명에 · 로컬 실행 파일을 `iac/` 실제 경로로 · 상태 값을 도메인 · API 와 같게 · 결과 표시 상태를 prd D4 로 · 결과 스냅샷에 D5 필드 · 07:00 명단 확정 절차) · 짝 문서: [HLD](../hld/platform.md) · [SPEC](../spec/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) v10 · 원본 산출물: `docs/outputs/feature-infra-arch/db/`

**세 줄 요약**
- 바뀌는 것: PostgreSQL 스키마(테이블 8개 · 하루 파티션 + persister dead-letter 1개)와 `worker: persister` 의 멱등 쓰기 규칙 · 처리하지 못한 메시지의 dead-letter(prd D20).
- 데이터 모델: 마스터(`groups` · `users` · `questions` · `served_questions`) + `game_date` 파티션(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`). CHECK 없이 백엔드가 검증하고 허용 값은 `COMMENT ON` 에 둔다.
- 구현 순서: 스키마 → 파티션 생성(`worker: member`) → `worker: persister` 멱등 upsert → 재적재(rehydrate — §4.2: Redis 가 비면 명단을 만드는 길이 PostgreSQL 그날 상태로 그룹 Hash 를 다시 올린다).

## 1. 모듈 · 파일
| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `iac/compose.yaml` | 수정 | 앱 이미지 4개를 `group-mission-<서비스>:${IMAGE_TAG:-local}` 로(T-PF-10 — SPEC §1 · §5). 프로젝트 이름은 `name: group-mission` 그대로 두고 `-p` · `COMPOSE_PROJECT_NAME` 이 덮어쓴다 |
| `iac/local.sh` | 수정 | 맨 앞에서 `IMAGE_TAG` · `COMPOSE_PROJECT_NAME` 을 정해 export 한다(SPEC §5 표 — `git rev-parse --git-dir` ≠ `--git-common-dir` 이면 워크트리, 브랜치 slug · 분리된 HEAD 는 폴더 이름). `iac/.env` 를 읽기 전에 셸 값(포트 · 태그 · 프로젝트)을 떠 두었다가 비어 있지 않으면 다시 씌운다 — 셸 > `iac/.env`. 모든 `docker compose` 호출과 `image prune` 라벨 필터가 이 값을 쓴다. 하위 명령 `env` 는 정한 값을 출력만 한다. 워크트리에서 포트가 기본값이면 `up` 이 겹침을 알린다 |
| `backend/infra/rdb/src/main/resources/schema.sql` | 수정 | 스키마 DDL — 아래 §2.1 그대로(`CREATE … IF NOT EXISTS` 꼴로 써서 `spring.sql.init.mode=always` 로 `api` 기동 때마다 적용 — 몇 번 적용해도 같다). auth-signup 일감이 `groups` · `users` 로 처음 만들고(LLD auth-signup §1), 나머지 테이블 6개 · 인덱스 · 오늘 파티션은 §5 1단계(T-PF-1)에서 더했다. persister dead-letter 테이블 `persister_dead_letters`(파티션 없음)는 T-PF-6 이 더했다 |
| `backend/infra/rdb/src/main/resources/schema.sql` · `…/infra/rdb/RdbDailyProgressStore.kt` (+ `SchemaLock.kt`) | 수정 (T-PF-12) | 스키마 잠금(§4.3) — `schema.sql` 의 첫 문장이 `SELECT pg_advisory_lock(<키>)`, 마지막 문장이 `pg_advisory_unlock(<키>)`(세션 잠금 — Spring 스크립트 실행기가 한 파일을 한 연결에서 돈다). `ensurePartitions`(Q9)는 테이블마다 `DO` 블록 한 문장 — 맨 앞에서 `pg_advisory_xact_lock(<키>)`(트랜잭션 잠금 — 문장이 끝나면 풀린다) 뒤 그 테이블 파티션 하나. 4개를 한 트랜잭션에 묶지 않는다(§4.3). 키는 `SchemaLock.KEY` = `1296651091`(ASCII `MISS`) 하나 — `schema.sql` 의 숫자와 같아야 한다(O36 테스트가 견준다) |
| `iac/compose.yaml` · `iac/local.sh` | 수정 (T-PF-12) | `api` 에 헬스체크(`curl -fsS http://localhost:8080/health` — 스키마 · 시드 적용이 끝나야 앱이 뜨므로 healthy = 스키마 준비됨), `worker` 의 `depends_on` 에 `api: service_healthy`(§4.3). `local.sh up` 은 `docker compose up` 이 실패해도(의존 서비스가 healthy 가 되지 못함) 최근 로그를 찍고 1 로 끝난다 |
| `backend/infra/rdb/src/main/resources/data.sql` | 새로 | 문제 시드(§2.2) — Spring 기본 데이터 스크립트 자리라 `schema.sql` 바로 뒤에 같은 기동에서 적용된다. `INSERT … SELECT generate_series … ON CONFLICT (pk_hash) DO NOTHING` 이라 다시 적용해도 행이 늘지 않는다 |
| `backend/support/common/…/common/error/ErrorCode.kt` | 수정 | [api-guide](../api-guide.md) §4 표의 코드 22개를 같은 순서로 갖는다 — T-PF-7 이 `INTERNAL_ERROR`(500) · `UNAVAILABLE`(503)을 끝에 더했고(prd D21 · §4.1), T-PF-8 이 스켈레톤 코드 `NOT_IMPLEMENTED`(501)와 그것을 던지던 `NotImplementedYet` 을 지웠다(오퍼레이션이 모두 구현돼 내는 곳이 없다 — 남기면 api-guide 표 · openapi enum 에 클라이언트가 받을 일 없는 코드가 남는다). 그 밖은 처음부터 다 갖고 있었다(기능 LLD 들이 「먼저 하는 쪽이 더한다」고 적은 `ALREADY_LOGGED_IN` · `NOT_PARTICIPANT` · `QUESTIONS_EXHAUSTED` · `QUESTION_MISMATCH` 포함). 표와 enum 을 견주는 계약 테스트만 더한다(ORACLE O12 — 표만 바뀌어도 다시 돈다) |
| `backend/support/common/…/common/error/DependencyUnavailableException.kt` | 새로 | 「의존 서비스를 지금 쓸 수 없다」 표지 예외(open). 우리 코드가 의존 서비스 장애를 알릴 때 이것을 상속한다 — `GroupChannelLostException`(core)이 상속한다. §4.1 |
| `backend/support/common/…/common/error/FailureClassifier.kt` | 새로 | `errorCodeOf(e)`: `BusinessException` 이면 그 코드, 원인 사슬에 의존 서비스 장애가 있으면 `UNAVAILABLE`, 아니면 `INTERNAL_ERROR`. 판별은 §4.1 표 — common 은 Spring · 드라이버에 기대지 않으므로 외부 타입은 클래스 이름(상위 클래스까지)으로 본다 |
| `backend/support/common/…/common/answer/AnswerNormalizer.kt` | 새로 | 정답 정규화 · 해시(prd R-A2 · D22 · D7) — `normalize(answer)` = D22 표의 공백 문자 33개 전부 제거 → `java.text.Normalizer` NFC → A-Z 만 a-z(로캘 소문자를 쓰지 않는다). `hash(questionId, answer)` = `SHA-256(UTF-8("<questionId>:<normalize(answer)>"))` 소문자 hex. 서버는 판정하지 않으므로 지금 쓰는 곳은 시드 검사(O14)뿐이고, 공유 벡터 `docs/api/answer-normalization-vectors.json` 으로 클라이언트와 결과를 맞춘다(O34) |
| `backend/core/domain/…/core/domain/event/GroupEvent.kt` | 수정 | `GroupChannelLostException` 이 `DependencyUnavailableException` 을 상속한다(구독 실패 · 끊김 · 구독 확인 시간 초과 → 503) |
| `backend/app/api/…/app/api/web/FallbackExceptionHandler.kt` · `backend/app/realtime/…/app/realtime/web/FallbackExceptionHandler.kt` | 새로 | `WebExceptionHandler`(`@Order(-2)` — Boot 기본 처리기 `-1` 앞). 컨트롤러의 `@RestControllerAdvice`(`GlobalExceptionHandler`)가 잡지 않은 예외와 WebFilter 예외를 §4.1 절차로 JSON 응답한다. 두 앱은 서로 기대지 못해(아키텍처 규칙) 같은 모양을 하나씩 둔다 |
| `backend/core/domain/…/core/config/GameProperties.kt` | 수정 | `clockOffset: Duration = 0`(설정 `game.clock-offset` — 시각 주입, HLD §8) · `bucketCount = 1024`. 계산 함수 `now(clock)` = `clock.instant() + clockOffset`(앱이 「지금」을 고를 때 쓰는 하나뿐인 길 — `Clock` 빈 자체는 오프셋을 모른다) · `clockOffsetUs`(Lua ARGV `offsetUs` — µs 아래는 내림) · `bucketOf(gid)` = `gid mod bucketCount`. 오프셋이 µs 로 넘치면 기동 때 실패한다. 환경 변수로 줄 때 이름은 `GAME_CLOCKOFFSET` · `GAME_BUCKETCOUNT` 다(`GAME_CLOCK_OFFSET` 은 `game.clock.offset` 으로 읽혀 무시된다). `api` · `realtime` · `worker` 는 같은 값을 가져야 시각 · 키가 맞는다. 기능 LLD 가 더하는 수치(`lateGrace` 등)는 그 일감이 더한다 |
| `backend/infra/redis/…/infra/redis/RedisKeys.kt` | 새로 | 버킷 · 키 이름 함수 — HLD §4.1 표의 키를 글자 그대로 만든다: `group(gid, date)` `g:{<b>}:<gid>:<date>` · `members(gid, date)` · `dayGroups(b, date)` · `events(b)` · `channel(gid)` · `ticket(t)` · `session(uid)` · `result(gid)` · `bank(uid, date)`. `<date>` 는 `YYYY-MM-DD`, 버킷은 `GameProperties.bucketOf`. 빈으로 등록한다(`RedisInfraAutoConfiguration`) |
| `backend/infra/redis/…/infra/redis/RedisScripts.kt` | 새로 | Lua 로더 — 하나만 둔다. `eval(name, keys, args)`: `lua/lib/*.lua` 를 모두 앞에 붙인 `lua/<name>.lua` 를 처음 쓸 때 `SCRIPT LOAD` 하고 `EVALSHA`. lib 는 빈을 만들 때 읽고 검사한다(규약 위반은 기동 실패). `NOSCRIPT`(Redis 재시작 · `SCRIPT FLUSH` · 장애 조치)면 다시 올리고 **1회만** 재시도한다. 반환은 목록(문자열 칸은 `String`, 정수 칸은 `Long`, 안쪽 배열은 `List`) — 스칼라 반환은 한 칸 목록이다. Lua 표 안의 `nil` 은 배열을 그 자리에서 자르고(조용히 짧아진다) `false` 는 RESP nil 이라 로더가 오류로 끝낸다 — 빈 칸은 `''` 로 돌려주고, 쓰는 쪽은 칸 수를 확인한다. 빈으로 등록한다 |
| `backend/infra/redis/src/main/resources/lua/lib/common.lua` | 새로 | `common.now(offsetUs)` = Redis `TIME` 의 `초 × 1e6 + µs` + `offsetUs`(`-?숫자` 꼴의 십진 정수가 아니면 — `1e3` · `0x10` · 빈 값 포함 — 스크립트 오류). 그 밖의 도우미(그룹 상태 · 그룹원 칸 · XADD · SPUBLISH)는 solve-signal 일감이 더한다(LLD solve-signal §1) |
| `backend/settings.gradle.kts` · `backend/app/worker/build.gradle.kts` · `…/app/worker/WorkerApplication.kt` | 새로 | 세 번째 앱 모듈 `app:worker`(HLD §5) 뼈대 — 웹 서버 없이(`spring.main.web-application-type: none`) `@EnableScheduling`. 붙들 웹 서버 스레드가 없어 `spring.main.keep-alive: true` 로 프로세스를 살려 둔다. 기능 일감(`worker: member` · `prepare-questions` · `deadline` · persister 처리기)은 이 모듈에 잡 · 처리기만 더한다 |
| `backend/app/worker/…/app/worker/persister/StreamPersister.kt` | 새로 · 수정 | `worker: persister` 소비 루프 — 버킷 Stream `ev:{<b>}` 을 consumer group `persister` 로 `XREADGROUP`, `type` 별 처리기(기능 LLD 가 정함 — HLD §4.2 표) 호출, 커밋 뒤 `XACK`, 기동 때와 30초마다 `XAUTOCLAIM`(idle 30초). 버킷 하나는 한 번에 한 소비자만 처리해 순서를 지킨다. 절차는 §3.1. 처리하지 못하는 메시지는 dead-letter 로 넘긴다(T-PF-6 — 포트 `DeadLetterStore` · 시각은 `Clock` 빈) |
| `backend/app/worker/…/app/worker/persister/PersisterEventHandler.kt` | 새로 | 처리기 계약(§3.1) — `types: Set<String>` · `suspend handle(event: StreamEvent)`(돌아오면 커밋됨). 고칠 수 없는 메시지는 `MalformedEventException`(→ dead-letter · T-PF-6). 기능 일감은 이 인터페이스의 빈을 `persister/` 에 더하기만 한다 |
| `backend/app/worker/…/app/worker/persister/PersisterProperties.kt` | 새로 · 수정 | 설정 `worker.persister.*` — `enabled`(true) · `instance-index`(0) · `instance-count`(1) · `parallelism`(16) · `batch-size`(100 · 1 ~ 1,000 — `persister_ack` 의 `unpack` 한도) · `idle-delay`(200ms) · `claim-interval`(30s) · `claim-min-idle`(30s) · `retry-backoff`(1s) · `retry-backoff-max`(30s) · `dead-letter-after`(3 · 1 이상 — T-PF-6 · §3.1 dead-letter) |
| `backend/app/worker/…/app/worker/persister/PersisterFailures.kt` (+ `PersisterEventHandler.kt` 의 `RetryableEventException`) | 새로 | 실패 분류(T-PF-6 · §3.1 dead-letter) — 예외가 dead-letter 횟수에 **들지 않는** 일시 장애인지. 예외와 원인 사슬의 클래스 계층에 §3.1 dead-letter 1 의 이름이 하나라도 있으면 세지 않는다. `app:worker` 는 저장소 라이브러리를 컴파일 의존으로 갖지 않아(포트만 쓴다 — 아키텍처 규칙) 클래스 이름으로 본다 |
| `backend/core/domain/…/core/domain/event/DeadLetter.kt` | 새로 | dead-letter 한 건 — `bucket` · `streamId` · `type` · `fields`(원본 칸) · `failureCount` · `lastError` · `firstFailedAt` · `lastFailedAt` |
| `backend/core/domain/…/core/port/DeadLetterStore.kt` | 새로 | `suspend put(letter: DeadLetter)` — 한 문장(= 한 트랜잭션), 돌아오면 커밋됨. 같은 (bucket, streamId) 는 한 행(멱등). 실패는 예외 |
| `backend/infra/rdb/…/infra/rdb/RdbDeadLetterStore.kt` (+ `RdbInfraAutoConfiguration` 빈) | 새로 · 수정 | 포트 구현 — §3.1 dead-letter 의 `INSERT … ON CONFLICT`. 칸은 `jsonb_object(text[], text[])` 로 JSON 객체를 만든다(JSON 라이브러리 없이) |
| `backend/core/domain/…/core/domain/event/StreamEvent.kt` | 새로 | 이벤트 Stream 메시지 한 건 — `id`(Stream id) · `fields`(HLD §4.2 의 칸 그대로, 문자열) · `type`. 지워진 항목(트림 뒤 PEL 에만 남은 id)은 `fields` 가 비어 있다 |
| `backend/core/domain/…/core/port/EventStreamConsumer.kt` | 새로 | persister 소비 포트(consumer group `persister` 고정) — `ensureGroup(b)` · `readNew(b, consumer, count)` · `readOwnPending(b, consumer, count)` · `claimIdle(b, consumer, minIdle, count, cursor)`(`XAUTOCLAIM` 한 번 — 커서를 돌려준다) · `ack(b, ids)`. 앱은 infra 를 직접 쓰지 않는다(아키텍처 규칙) |
| `backend/infra/redis/…/infra/redis/RedisEventStreamConsumer.kt` (+ `lua/persister_group.lua` · `persister_read.lua` · `persister_claim.lua` · `persister_ack.lua`) | 새로 | 포트 구현 — Spring Data Redis 에 `XAUTOCLAIM` 이 없어 Stream 명령을 모두 `RedisScripts` Lua 로 돈다(`BLOCK` 없음). `persister_ack` 는 `XACK` 과 트림(`XTRIM MINID` — 이번 마지막 id 와 그룹의 가장 작은 pending id 중 작은 것)을 한 번에 한다 |
| `compose.yaml` (루트) | 새로 | `iac/compose.yaml` 을 include — 루트에서 `docker compose up` 한 번 |
| `backend/core/domain/…/core/domain/game/GameDay.kt` · `group/Group.kt` · `progress/MissionProgress.kt` | 삭제 · 수정 | T-PF-8 — 스켈레톤이 501 을 던지던 자리표시를 지운다: `GameDay` · `GamePhase`(파일째) · `Group.rosterOn` · `MissionProgress.start`. 어디서도 부르지 않고, 명단 · 시작 · 마감 판정은 Lua 한 벌이 맡는다(LLD game-session · solve-signal · deadline-result) |
| `backend/app/api/…/RouteSmokeTest.kt` · `backend/app/realtime/…/RouteSmokeTest.kt` | 수정 | T-PF-8 — 「구현 전 오퍼레이션은 501」 검사(빈 목록이 됨)를 ORACLE C1 로 바꾼다: openapi 응답 키에 `501` 이 없고 계약 원문에 `NOT_IMPLEMENTED` 가 없으며 `ErrorCode` 에 HTTP 501 이 없다 |
| `iac/compose.yaml` | 새로 · 수정 | `redis` · `postgres` · `api` · `realtime` · `worker` · `web` ([HLD](../hld/platform.md) §8). `worker` 는 §5 3단계(T-PF-3)에서 더했다 — 웹 서버가 없어 헬스 주소 대신 컨테이너가 `running` 인지 본다 |
| `iac/docker/backend.Dockerfile` (+ `.dockerignore`) | 새로 · 수정 | `backend/` 를 빌드해 타깃 `api` · `realtime` · `worker` 이미지 |
| `iac/docker/frontend.Dockerfile` · `default.conf` (+ `.dockerignore`) | 새로 | `frontend/` 정적 빌드 → nginx(SPA 경로는 `index.html`) |
| `iac/local.sh` | 새로 · 수정 | 띄우기 · 헬스 확인(+ `worker` 가 `running`) · 내리기 ([SPEC](../spec/platform.md) §3) |
| `backend/core/domain/…/core/domain/roster/GroupRestore.kt` | 새로 (T-PF-5) | 재적재 — PostgreSQL 그날 기록(`GroupDayRecord` · `MemberDayRecord`)을 그룹 Hash 의 덮을 칸 · `version` · `rerank` 로 바꾸는 순수 함수(§4.2 칸 매핑 · 재적재 판정). 기록이 없으면 `null`(처음 만드는 명단) |
| `backend/core/domain/…/core/port/DailyProgressStore.kt` · `backend/infra/rdb/…/RdbDailyProgressStore.kt` | 수정 (T-PF-5) | `loadDayStates(date, gids)` = §4.2 R1 · R2 — 그룹마다 `GroupDayRecord` |
| `backend/core/domain/…/core/port/RosterRepository.kt` · `backend/infra/redis/…/RedisRosterRepository.kt` · `lua/roster.lua` | 수정 (T-PF-5) | `createIfAbsent(roster, restore)` — `restore` 가 있으면 `roster.lua` 꼬리(§4.2)로 넘긴다. `markConfirmed(date)` · `countUnmarked(date)` — 버킷 표시 `rk:{<b>}:<date>`(§4.2 감시) |
| `backend/core/domain/…/core/service/RosterService.kt` · `backend/app/worker/…/member/MemberJob.kt` · `MemberProperties.kt` | 수정 (T-PF-5) | 명단을 만들 때 재적재(§4.2 발동 1 · 2) · 버킷 표시 쓰기 · 감시(`watch-interval` 10초 · `watchOnce(date)`) · 마감 따라잡기(§4.2 발동 3) |
| `backend/core/domain/…/core/config/GameProperties.kt` | 수정 (T-PF-13) | 런타임 이동량 `shift`(`ClockShift` — 생성자 밖 필드라 설정으로 바인딩되지 않는다). `now(clock)` = `clock.instant() + clockOffset + shift`, `clockOffsetUs` = `clock-offset` µs + `shift` µs(Lua ARGV `offsetUs`). 꺼진 앱은 `shift` 가 언제나 0 |
| `backend/core/domain/…/core/config/ClockShift.kt` · `DemoClockProperties.kt` | 새로 (T-PF-13) | `ClockShift` — µs 값 하나를 든 `StateFlow`(읽기 · 바꾸기 · 바뀜 기다리기). `DemoClockProperties`(`game.demo.*` — SPEC §6.1) |
| `backend/core/domain/…/core/domain/game/DemoClockRules.kt` | 새로 (T-PF-13) | 순수 함수 — 첫 이동량(§4.4-1) · 이동 검사 · 프리셋 시각(§4.4-3) |
| `backend/core/domain/…/core/domain/game/GameClock.kt` | 수정 (T-PF-13) | `suspend fun pauseUntil(at, max)` — 게임 시각 `at` 까지 실제 시간으로 최대 `max` 기다리되 `shift` 가 바뀌면 바로 깬다. `worker` 잡 3개(`member` · `prepare-questions` · `deadline`)의 기다림이 이것을 쓴다 |
| `backend/core/domain/…/core/port/DemoClockStore.kt` · `backend/infra/redis/…/RedisDemoClockStore.kt` | 새로 (T-PF-13) | Redis 키 `demo:clock`(µs 십진 정수) — `get` · `setIfAbsent`(`SET NX`) · `compareAndSet`(작은 Lua — 읽은 값과 같을 때만 `SET`) · `shortenSessions`(`SCAN sess:{*}` → 남은 TTL 을 이동만큼 줄임) |
| `backend/core/domain/…/core/service/DemoClockService.kt` | 새로 (T-PF-13) | `game.demo.enabled=true` 일 때만 빈. 빈을 만들 때 첫 이동량을 정해 반영하고(잡의 `SmartLifecycle.start` 보다 먼저), `sync-interval` 마다 다시 읽는다. `move(target)` 은 검사 → `compareAndSet` → 자기 `shift` 를 바로 바꾸고 세션 키 TTL 을 줄인다. `shift` 반영은 앞으로만(더 작은 값은 버린다) |
| `backend/app/api/…/controller/DevClockController.kt` · `dto/DevClockDtos.kt` | 새로 (T-PF-13) | `GET` · `POST /api/v1/dev/clock` — `@ConditionalOnProperty(game.demo.enabled)`. 인증 필터는 데모가 켜졌을 때만 이 경로를 보지 않는다(`AccessTokenWebFilter(demoEnabled)` — 꺼지면 다른 보호 경로처럼 401) |

**Lua lib 모듈 규약** (`RedisScripts` 가 강제한다)
- `lua/lib/<이름>.lua` 의 파일 이름(확장자 뺀 것)이 모듈 이름이고 Lua 식별자여야 한다(`[A-Za-z_][A-Za-z0-9_]*`).
- 로더는 맨 앞에 `local <모듈1>, <모듈2>, …` 를 한 줄로 선언하고, lib 파일(이름 순) · 본문 순으로 이어 붙인다. lib 파일은 `<이름> = {}` 로 그 자리를 채우고 `function <이름>.f(…)` 로 함수를 단다 — `local` 을 다시 붙이지 않는다.
- 그래서 lib 끼리는 파일 순서와 상관없이 서로 부를 수 있다(예: `decide.lua` → `ranking.lua`). Redis 는 스크립트의 전역 변수 생성을 막으므로 전역은 쓰지 않는다.
- Lua 숫자는 배정밀도라 epoch µs(약 1.8e15)를 정확히 담지만 `tostring` 은 지수 표기(`1.8e+15`)를 낸다 — 문자열로 쓸 때는 `string.format('%.0f', t)` 를 쓴다.

## 2. 데이터 모델
| 관계 | 카디널리티 | 키 |
|---|---|---|
| groups → users | 1 : 2~10 | `users.group_id` |
| users → served_questions | 1 : N (평생) | `served_questions.user_id` |
| questions → served_questions | 1 : N | `served_questions.question_id` |
| users → daily_progress | 1 : 하루 1 | `(game_date, user_id)` |
| daily_progress → solve_signals | 1 : 0~3 | `(game_date, user_id, question_no)` |
| groups → group_results | 1 : 하루 0~1 | `(game_date, group_id)` |
| group_results → group_result_members | 1 : 2~10 | `(game_date, group_id)` |

| 테이블 | 무엇 | 쓰는 쪽 | 규모(산술) |
|---|---|---|---|
| `groups` | 그룹. 그룹명 유일(R-G2) · 정원 10(R-G3) | api(가입) | 최대 125만 행 |
| `users` | 유저 · 자격 증명 · 소속 그룹 · 가입 시각(그날 그룹원 판정 R-G4) | api | 250만 행 |
| `questions` | 문제은행. 정답과 정답 해시를 따로 저장(R-A3) | api(캐시 적재) | 운영 데이터 |
| `served_questions` | 유저에게 낸 문제(맞힘 · 패스) — 평생 중복 금지(R-B7 · R-S2 · R-P3) | `worker: persister` | 하루 최대 1,000만 행 누적 |
| `daily_progress` | 유저 하루 상태 · 랭킹 키 | `worker: persister` | 하루 250만 행 |
| `solve_signals` | 반영된 풀이 신호 원장(감사 · 재적재) | `worker: persister` | 하루 최대 750만 행 |
| `group_results` | 그룹 하루 결과 — 정확히 한 번(NFR-2) | `worker: persister` | 하루 최대 125만 행 |
| `group_result_members` | 결과 확정 시점의 그룹 내 랭킹 스냅샷(NFR-4) | worker → api(결과 조회) | 하루 250만 행 |
| `persister_dead_letters` | `worker: persister` 가 처리하지 못한 Stream 메시지 원본(prd D20 — 재처리는 범위 밖) | `worker: persister` | 평소 0 행 — 처리기 버그 · 계약 어긋남일 때만(파티션 없음 · §3.1) |

- **CHECK 제약은 두지 않는다.** 값 범위 · 허용 값 검증은 모두 백엔드가 한다 — 규칙이 바뀔 때(정원 · 상태 값 · 문제 번호 범위) 마이그레이션 없이 확장하기 위해서다. 허용 값 · 의미는 DDL 의 `COMMENT ON` 에 남긴다(검증 규칙의 정본). PK · UNIQUE · FK · NOT NULL 은 정합성(NFR-2 · NFR-3)의 마지막 방어선이라 남긴다.
- 하루 단위 테이블(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`)은 `game_date` 범위 파티션 — 오래된 날은 파티션째 보관 · 삭제. `persister_dead_letters` 는 파티션하지 않는다 — 평소 비어 있고 쌓여도 처리기 버그 하나에 그 `type` 메시지 수만큼이라 하루 단위로 지울 일이 없고, 운영자가 날짜와 상관없이 (bucket, stream_id) · `event_type` 으로 찾는다. 다시 넣는 프로세스(범위 밖)가 생기면 그때 보관 기간을 정한다.
- 진행 중 판정은 Redis 가 하고, PostgreSQL 은 Stream 을 받아 **멱등 upsert** 로 따라간다([HLD](../hld/platform.md) §3). 모든 쓰기에 자연키 PK 와 `ON CONFLICT` 가 있어 같은 이벤트를 두 번 받아도 결과가 같다.
- PRD 결정이 들어간 곳: 진입 신호 기록(D6)은 `served_questions.kind = SHOWN` · 그룹명 비교 키(D11)는 `groups.name_key` · 정원 기준(D12)은 `groups.member_count` · 지각(D8)은 `daily_progress.is_late`.

### 2.1 DDL
```sql
-- ============================================================
-- 값 범위 · 허용 값은 CHECK 로 두지 않고 백엔드가 검증한다(확장성).
-- 각 컬럼의 허용 값 · 의미는 COMMENT 에 적는다 — 이 주석이 검증 규칙의 정본이다.
-- ============================================================

CREATE TABLE groups (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(30)  NOT NULL,
    name_key      VARCHAR(30)  NOT NULL UNIQUE,
    member_count  SMALLINT     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE  groups              IS '그룹. 유저는 가입 때 새 그룹을 만들거나 기존 그룹에 들어가며, 탈퇴 · 이동은 없다(R-G7).';
COMMENT ON COLUMN groups.id           IS '그룹 PK.';
COMMENT ON COLUMN groups.name         IS '화면에 보이는 그룹명(앞뒤 공백만 지운 입력 표기). 2~20자, 한글 · 영문 · 숫자 · 공백 · - · _ (prd D11).';
COMMENT ON COLUMN groups.name_key     IS '유일성 비교용 키 = 앞뒤 공백 제거 + 영문 소문자(R-G2 · prd D11). UNIQUE 로 동시 생성 경합을 막는다.';
COMMENT ON COLUMN groups.member_count IS '현재 소속 인원. 허용 값 0~10 — 10 이면 11번째 가입을 「그룹이 꽉 찼다」로 거부(R-G3). 증가는 백엔드가 조건부 UPDATE(member_count < 10)로 원자 처리. 그날 참가하지 못하는 06:50 이후 가입자도 센다(prd D12 · D24).';
COMMENT ON COLUMN groups.created_at   IS '그룹 생성 시각(KST 판정은 백엔드).';

CREATE TABLE users (
    id             BIGSERIAL    PRIMARY KEY,
    login_id       VARCHAR(32)  NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,
    group_id       BIGINT       NOT NULL REFERENCES groups(id),
    joined_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX users_group_joined_idx ON users (group_id, joined_at);
COMMENT ON TABLE  users               IS '유저 · 자격 증명 · 소속 그룹(FR-2 · FR-8).';
COMMENT ON COLUMN users.id            IS '유저 PK(내부용). 클라이언트에 보이는 유저 ID 는 login_id 다.';
COMMENT ON COLUMN users.login_id      IS '로그인 아이디. 유일. 현황판 · 결과의 유저 ID(API userId)로 보인다(prd D5). 길이 · 허용 문자는 백엔드 검증.';
COMMENT ON COLUMN users.password_hash IS '비밀번호 해시(bcrypt 또는 argon2). 평문은 저장하지 않는다.';
COMMENT ON COLUMN users.group_id      IS '소속 그룹. 가입 이후 바뀌지 않는다.';
COMMENT ON COLUMN users.joined_at     IS '가입 시각. 그 날짜 06:50 KST 전이면 그날 그룹원(R-G4 · R-G5 · prd D24), 06:50 부터면 다음 날부터.';

CREATE TABLE questions (
    id           BIGSERIAL    PRIMARY KEY,
    pk_hash      CHAR(64)     NOT NULL UNIQUE,
    difficulty   SMALLINT     NOT NULL,
    category     VARCHAR(10)  NOT NULL,
    body         TEXT         NOT NULL,
    answer       TEXT         NOT NULL,
    answer_hash  CHAR(64)     NOT NULL
);
CREATE INDEX questions_difficulty_idx ON questions (difficulty);
COMMENT ON TABLE  questions             IS '문제은행. 정답과 정답 해시를 따로 저장하고 클라이언트에는 해시만 내려준다(R-A3).';
COMMENT ON COLUMN questions.id          IS '내부 PK(다른 테이블 FK 용). 클라이언트에 노출하지 않는다.';
COMMENT ON COLUMN questions.pk_hash     IS '클라이언트에 내려주는 문제 고유 PK(hash · 64자 hex). 풀이 · 패스 신호의 문제 PK 가 이 값이다(R-B2).';
COMMENT ON COLUMN questions.difficulty  IS '난이도. 허용 값 1 · 2 · 3 — 문제 번호와 같다(1번=1, 2번=2, 3번=3, R-B5).';
COMMENT ON COLUMN questions.category    IS '분야. 허용 값 POLITICS(정치) · CURRENT(시사) · SOCIETY(사회) · GENERAL(상식).';
COMMENT ON COLUMN questions.body        IS '문제 본문(주관식).';
COMMENT ON COLUMN questions.answer      IS '실제 정답. 내려주지 않는다(R-A3).';
COMMENT ON COLUMN questions.answer_hash IS '정답 해시 — 정규화(prd R-A2 · D22: 공백 문자 33개 전부 제거 → NFC → A-Z 만 소문자) 뒤 해시. 값 = SHA-256(UTF-8("<pk_hash>:<정규화한 답>")) 소문자 hex(prd D7).';

CREATE TABLE served_questions (
    user_id      BIGINT       NOT NULL REFERENCES users(id),
    question_id  BIGINT       NOT NULL REFERENCES questions(id),
    game_date    DATE         NOT NULL,
    question_no  SMALLINT     NOT NULL,
    kind         VARCHAR(8)   NOT NULL,
    served_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, question_id)
);
COMMENT ON TABLE  served_questions             IS '유저에게 이미 낸 문제 — 진입 신호로 화면에 나간 문제 전부(prd D6). PK(user_id, question_id)로 평생 중복 금지(R-B7 · R-S2 · R-P3). 문제은행을 줄 때 여기 있는 문제를 뺀다(R-B3).';
COMMENT ON COLUMN served_questions.user_id     IS '유저.';
COMMENT ON COLUMN served_questions.question_id IS '낸 문제(questions.id).';
COMMENT ON COLUMN served_questions.game_date   IS '낸 게임 날짜(KST).';
COMMENT ON COLUMN served_questions.question_no IS '그날 몇 번 문제 자리였는지. 허용 값 1 · 2 · 3.';
COMMENT ON COLUMN served_questions.kind        IS '이 문제의 마지막 상태. 허용 값 SHOWN(진입 신호로 나감 — 처음 기록) · SOLVED(맞힘 — 풀이 신호) · PASSED(패스로 넘김 — 패스 신호). SHOWN 으로 들어간 행을 SOLVED · PASSED 로 갱신한다(prd D6).';
COMMENT ON COLUMN served_questions.served_at   IS '서버가 그 신호를 받은 시각(Redis TIME).';

CREATE TABLE daily_progress (
    game_date          DATE         NOT NULL,
    user_id            BIGINT       NOT NULL REFERENCES users(id),
    group_id           BIGINT       NOT NULL REFERENCES groups(id),
    progress           SMALLINT     NOT NULL DEFAULT -1,
    status             VARCHAR(16)  NOT NULL DEFAULT 'NOT_STARTED',
    started_at         TIMESTAMPTZ,
    is_late            BOOLEAN,
    last_solved_at     TIMESTAMPTZ,
    pass_question_no   SMALLINT,
    passed_question_id BIGINT       REFERENCES questions(id),
    cheat_failed_at    TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (game_date, user_id)
) PARTITION BY RANGE (game_date);
CREATE INDEX daily_progress_group_idx ON daily_progress (game_date, group_id);
COMMENT ON TABLE  daily_progress                    IS '유저의 하루 미션 상태 · 랭킹 키. 진행 중 정본은 Redis 그룹 Hash 이고, 이 테이블은 Stream 을 받아 멱등 upsert 로 따라간다. game_date 범위 파티션.';
COMMENT ON COLUMN daily_progress.game_date          IS '게임 날짜(KST).';
COMMENT ON COLUMN daily_progress.user_id            IS '유저.';
COMMENT ON COLUMN daily_progress.group_id           IS '그날의 소속 그룹(그룹별 조회용 비정규화).';
COMMENT ON COLUMN daily_progress.progress           IS '진행도 = 그날 푼 문제 수. 허용 값 -1 · 0 · 1 · 2 · 3. -1 = 미션 시작 전(현황판 표기 0), 0 = 문제은행을 받음, 3 = 미션 성공. 줄어들지 않는다(GREATEST 로만 갱신).';
COMMENT ON COLUMN daily_progress.status             IS '유저 미션 상태 — 도메인 board.MemberStatus · API MemberStatus 와 같은 값. 허용 값 NOT_STARTED(시작 전 · 진행도 -1) · IN_PROGRESS(진행도 0~2) · SUCCEEDED(3문제 완료) · CHEAT_FAILED(로컬 스토리지 빔으로 실패 처리, R-F2) · FAILED(마감 확정 때 NOT_STARTED · IN_PROGRESS 였음 — prd D17). SUCCEEDED · CHEAT_FAILED 는 「완료」이며 다시 바뀌지 않는다(성공 뒤 실패 신호는 무시 — prd D9).';
COMMENT ON COLUMN daily_progress.started_at         IS '미션 시작 시각 = 07:00 이후 처음 문제은행을 받은 서버 시각(R-B1). NULL = 시작 전.';
COMMENT ON COLUMN daily_progress.is_late            IS '지각 여부(진행도 0 동률 정렬용, §6). started_at 이 07:00:03 보다 늦으면 TRUE(prd D8). NULL = 시작 전.';
COMMENT ON COLUMN daily_progress.last_solved_at     IS '마지막으로 반영된 풀이 신호의 서버 도착 시각(Redis TIME). 랭킹 2순위 키(R-S5). successTime 은 쓰지 않는다.';
COMMENT ON COLUMN daily_progress.pass_question_no   IS '패스를 쓴 문제 번호. 허용 값 NULL(안 씀) · 1 · 2 · 3. 하루 1회 — 이미 값이 있으면 2번째 패스는 거부(R-P1 · R-P5). 랭킹 3순위 키(높은 번호에서 쓴 쪽이 위).';
COMMENT ON COLUMN daily_progress.passed_question_id IS '패스로 넘긴 문제. pass_question_no 와 함께 채워진다.';
COMMENT ON COLUMN daily_progress.cheat_failed_at    IS '부정 실패 처리 시각. status = CHEAT_FAILED 일 때만 값이 있다. 부정 실패 유저는 랭킹 꼴등 동률(§6 0순위).';
COMMENT ON COLUMN daily_progress.updated_at         IS '이 행이 마지막으로 반영된 시각(`worker: persister` 기준).';

CREATE TABLE solve_signals (
    game_date     DATE         NOT NULL,
    user_id       BIGINT       NOT NULL,
    question_no   SMALLINT     NOT NULL,
    question_id   BIGINT       NOT NULL REFERENCES questions(id),
    success_time  TIMESTAMPTZ  NOT NULL,
    click_time    TIMESTAMPTZ  NOT NULL,
    arrived_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (game_date, user_id, question_no),
    FOREIGN KEY (game_date, user_id) REFERENCES daily_progress (game_date, user_id)
) PARTITION BY RANGE (game_date);
COMMENT ON TABLE  solve_signals              IS '반영된 풀이 신호 원장(감사 · Redis 재적재용). 거부된 신호는 남기지 않는다. PK 로 같은 번호는 한 번만(NFR-3). game_date 범위 파티션.';
COMMENT ON COLUMN solve_signals.game_date    IS '게임 날짜(KST).';
COMMENT ON COLUMN solve_signals.user_id      IS '유저.';
COMMENT ON COLUMN solve_signals.question_no  IS '푼 문제 번호. 허용 값 1 · 2 · 3, 그리고 반영 당시 진행도 + 1 이어야 한다(순서 · R-S4).';
COMMENT ON COLUMN solve_signals.question_id  IS '푼 문제(클라이언트가 보낸 pk_hash 를 풀어 저장).';
COMMENT ON COLUMN solve_signals.success_time IS '클라이언트가 보낸 맞힌 시각. 조작 가능해 랭킹에 쓰지 않는다(기록만).';
COMMENT ON COLUMN solve_signals.click_time   IS '클라이언트가 보낸 제출 클릭 시각. 마감 가드(D1) 판정에만 쓴다 — 08:00:00~08:00:03 도착분은 이 값이 08:00:00 이전일 때만 반영.';
COMMENT ON COLUMN solve_signals.arrived_at   IS '서버 도착 시각(Redis TIME, 단일 시계). 마감 판정과 랭킹의 기준.';

CREATE TABLE group_results (
    game_date     DATE         NOT NULL,
    group_id      BIGINT       NOT NULL REFERENCES groups(id),
    result        VARCHAR(16)  NOT NULL,
    completed_at  TIMESTAMPTZ  NOT NULL,
    member_count  SMALLINT     NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (game_date, group_id)
) PARTITION BY RANGE (game_date);
CREATE INDEX group_results_group_idx ON group_results (group_id, game_date DESC);
COMMENT ON TABLE  group_results              IS '그룹의 하루 결과. PK(game_date, group_id) + ON CONFLICT DO NOTHING 으로 정확히 한 번 기록(NFR-2). 그날 그룹원 1명인 그룹은 행이 없다(R-G6). game_date 범위 파티션.';
COMMENT ON COLUMN group_results.game_date    IS '게임 날짜(KST).';
COMMENT ON COLUMN group_results.group_id     IS '그룹.';
COMMENT ON COLUMN group_results.result       IS '결과. 허용 값 SUCCEEDED(그날 그룹원 전원 미션 성공) · FAILED_IMMEDIATE(전원 완료인데 부정 실패가 한 명 이상, R-F6) · FAILED_DEADLINE(마감 확정 때 아직 진행 중 — 08:00, 3번 진행 중 그룹원이 있으면 08:00:03, R-D3 · prd D17). API GroupStatus 로는 SUCCEEDED → SUCCEEDED, FAILED_* → FAILED.';
COMMENT ON COLUMN group_results.completed_at IS '결과가 확정된 서버 시각(Redis TIME). SUCCEEDED 면 그룹 완료 시각.';
COMMENT ON COLUMN group_results.member_count IS '그날의 그룹원 수(그 날짜 06:50 전 가입자 — prd D24). 허용 값 2~10.';
COMMENT ON COLUMN group_results.recorded_at  IS 'PostgreSQL 에 기록된 시각(`worker: persister`).';

CREATE TABLE group_result_members (
    game_date     DATE         NOT NULL,
    group_id      BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    rank          SMALLINT     NOT NULL,
    progress      SMALLINT     NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    started       BOOLEAN      NOT NULL,
    last_solved_at TIMESTAMPTZ,
    pass_question_no SMALLINT,
    cheat_failed  BOOLEAN      NOT NULL,
    PRIMARY KEY (game_date, user_id),
    FOREIGN KEY (game_date, group_id) REFERENCES group_results (game_date, group_id)
) PARTITION BY RANGE (game_date);
CREATE INDEX group_result_members_group_idx ON group_result_members (game_date, group_id);
COMMENT ON TABLE  group_result_members          IS '그룹 내 랭킹 스냅샷. 결과 확정 때 넣고 08:00:03 랭킹 확정 때 확정값으로 덮는다(prd D17). 결과 조회는 이것만 읽어 최종 상태와 일치시킨다(NFR-4). game_date 범위 파티션.';
COMMENT ON COLUMN group_result_members.game_date IS '게임 날짜(KST).';
COMMENT ON COLUMN group_result_members.group_id  IS '그룹.';
COMMENT ON COLUMN group_result_members.user_id   IS '그날의 그룹원.';
COMMENT ON COLUMN group_result_members.rank      IS '그룹 내 순위. 허용 값 1~10, 동률은 같은 값(§6 규칙으로 계산).';
COMMENT ON COLUMN group_result_members.progress  IS '확정 시점 진행도. 허용 값 -1 · 0 · 1 · 2 · 3(화면 표기는 -1 → 0).';
COMMENT ON COLUMN group_result_members.started          IS '미션 시작 여부 — false 면 진행도 -1(API started · prd D5).';
COMMENT ON COLUMN group_result_members.last_solved_at   IS '마지막 풀이 신호의 서버 도착 시각(API lastSolvedAt · prd D5).';
COMMENT ON COLUMN group_result_members.pass_question_no IS '패스를 쓴 문제 번호. 허용 값 NULL · 1 · 2 · 3(API passedAtQuestionNo · prd D5).';
COMMENT ON COLUMN group_result_members.cheat_failed     IS '부정 실패 여부(prd D5). API 에는 별도 필드 없이 status = CHEAT_FAILED 로 나간다. 결과 확정 뒤라 모두에게 그대로 보인다.';
COMMENT ON COLUMN group_result_members.status    IS '결과 화면에 보이는 상태. 허용 값 SUCCEEDED · CHEAT_FAILED · FAILED(그룹원 상태 그대로 — prd D17). 화면에는 CHEAT_FAILED · FAILED 모두 「실패」(D4).';

-- persister dead-letter(prd D20 · §3.1) — 하루 단위가 아니라 파티션 없음.
CREATE TABLE persister_dead_letters (
    bucket           SMALLINT     NOT NULL,
    stream_id        VARCHAR(41)  NOT NULL,
    event_type       TEXT,
    fields           JSONB        NOT NULL,
    failure_count    INT          NOT NULL,
    last_error       TEXT         NOT NULL,
    first_failed_at  TIMESTAMPTZ  NOT NULL,
    last_failed_at   TIMESTAMPTZ  NOT NULL,
    dead_lettered_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket, stream_id)
);
COMMENT ON TABLE  persister_dead_letters                  IS '`worker: persister` 가 처리하지 못한 Stream 메시지(prd D20) — 처리기가 dead-letter-after(기본 3)번 실패했거나 형식 오류인 메시지를 원본 그대로 쌓고 ACK 한다. 다시 넣는 처리 프로세스는 범위 밖(운영자가 SQL 로 본다). 양이 적어 파티션하지 않는다.';
COMMENT ON COLUMN persister_dead_letters.bucket           IS 'Stream 버킷 b(ev:{b}). 허용 값 0 ~ game.bucket-count − 1.';
COMMENT ON COLUMN persister_dead_letters.stream_id        IS 'Stream id(<ms>-<seq>). (bucket, stream_id) 가 메시지 하나 — 같은 메시지를 다시 쌓아도 한 행(ON CONFLICT).';
COMMENT ON COLUMN persister_dead_letters.event_type       IS '칸 type(HLD platform §4.2). 칸이 없던 메시지면 NULL.';
COMMENT ON COLUMN persister_dead_letters.fields           IS 'Stream 칸 전체(type 포함) — 이름 → 문자열 값의 JSON 객체.';
COMMENT ON COLUMN persister_dead_letters.failure_count    IS '쌓을 때까지 센 실패 수 — dead-letter-after 이상(형식 오류는 1). DB · Redis 연결 장애는 세지 않는다. 다시 쌓이면 큰 값.';
COMMENT ON COLUMN persister_dead_letters.last_error       IS '마지막 실패 사유 — 처리기 없음이면 unhandled type, 그 밖은 예외 클래스와 메시지(앞 2,000자).';
COMMENT ON COLUMN persister_dead_letters.first_failed_at  IS '센 실패 중 처음 것의 시각(worker 시계). 다시 쌓이면 이른 값.';
COMMENT ON COLUMN persister_dead_letters.last_failed_at   IS '센 실패 중 마지막 것의 시각(worker 시계). 다시 쌓이면 늦은 값.';
COMMENT ON COLUMN persister_dead_letters.dead_lettered_at IS '이 행을 처음 쌓은 시각(DB 시계).';

-- 하루 파티션 — 이름 <테이블>_YYYYMMDD · 범위 [D, D+1).
-- 기동 때 KST 오늘 · 내일 것을 만든다(schema.sql — 기동한 채 자정을 한 번 넘겨도 쓰기가 막히지 않게).
-- 그 뒤의 날은 `worker: member` 가 전날 미리 만든다(T-GS-5 · Q9).
-- Spring 스크립트 분리기는 $$ 를 모르므로 본문을 작은따옴표로 감싼다(안의 따옴표는 '' 두 개).
DO '
DECLARE
    today date := (now() AT TIME ZONE ''Asia/Seoul'')::date;
    d date;
    t text;
BEGIN
    FOREACH d IN ARRAY ARRAY[today, today + 1] LOOP
        FOREACH t IN ARRAY ARRAY[''daily_progress'', ''solve_signals'', ''group_results'', ''group_result_members''] LOOP
            EXECUTE format(''CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)'',
                           t || ''_'' || to_char(d, ''YYYYMMDD''), t, d, d + 1);
        END LOOP;
    END LOOP;
END';
```

- `schema.sql` 에서는 위 문장마다 `CREATE TABLE IF NOT EXISTS` · `CREATE INDEX IF NOT EXISTS` 로 쓴다(`COMMENT ON` 은 다시 적용해도 같다). 파티션 부모의 인덱스는 PostgreSQL 이 파티션마다 만든다(`daily_progress_20261111_game_date_group_id_idx` 꼴 — 이름은 PostgreSQL 이 짓는다).
- 기동 때 만드는 파티션의 「오늘」은 DB 시계(`now()`)의 KST 날짜다 — 앱의 시각 주입(`game.clock-offset`)은 모른다. 오프셋으로 날짜가 바뀌는 실행에서 필요한 날의 파티션은 `worker: member` 가 만든다.

### 2.2 문제 시드 (`data.sql`)
실제 시사 문제가 아니라 **생성 규칙으로 만든 문제**다(로컬 실행 · 테스트용 — 문제 등록 도구는 목표 밖 — HLD game-session 세 줄 요약).

| 칸 | 규칙 (`d` = 난이도 1~3, `n` = 1~120) |
|---|---|
| 수 | 난이도마다 120개 = 문제은행 20개 × 6벌(평생 중복 금지 R-B7 로 한 유저에게서 하루에 난이도마다 많아야 2개 — 낸 문제 1 + 패스로 넘긴 문제 1 — 씩 빠지니 여러 날을 돌린다). game-session ORACLE 고정 데이터(난이도마다 30개)보다 많다 |
| `pk_hash` | `SHA-256(UTF-8("seed:question:<d>:<n>"))` 소문자 hex — 다시 적용해도 같은 값이라 멱등이다. 이미 있는 `pk_hash` 는 `NOT EXISTS` 로 먼저 걸러(`ON CONFLICT` 만 쓰면 걸러질 행도 `id` 시퀀스를 한 칸씩 쓴다) 기동마다 `id` 가 비지 않고, 동시 기동 경합은 `ON CONFLICT (pk_hash) DO NOTHING` 이 막는다 |
| `category` | `n` 차례로 `POLITICS` · `CURRENT` · `SOCIETY` · `GENERAL` |
| `answer` | `Mission <d>-<nnn> 정답`(`nnn` = 0 채운 세 자리) — 대문자 · 공백이 있어 정규화(R-A2)를 거쳐야 맞는다. 문자는 ASCII 영숫자 · `-` · U+0020 · 한글 완성형만 쓴다 — 이 범위에서는 D22 의 NFC 가 아무것도 바꾸지 않아 옛 규칙(공백 제거 → 소문자)과 결과가 같다. 다른 문자를 쓰는 시드를 넣어도 SQL 이 D22 를 그대로 하므로 틀어지지 않는다(O35) |
| `body` | `[시드 <d>-<nnn> · <분야>] 다음 말을 그대로 입력하세요: <answer>` — 본문에 답이 보여 사람이 손으로 풀어 볼 수 있다 |
| `answer_hash` | `SHA-256(UTF-8("<pk_hash>:<정규화한 answer>"))` 소문자 hex(prd D7). 정규화는 prd D22 를 SQL 로 그대로 한다 — `translate(normalize(regexp_replace(answer, '[<D22 문자 33개>]', '', 'g'), NFC), 'A…Z', 'a…z')`. 공백 문자는 `[[:space:]]`(로캘 · 버전마다 범위가 다르다) 대신 `\uXXXX` 로 하나씩 적고, 소문자는 `lower`(DB 콜레이션을 따른다) 대신 `translate` 로 A-Z 만 바꾼다. `normalize(…, NFC)` 는 DB 인코딩이 UTF8 이어야 한다(PostgreSQL 13+). 정규식의 `\uXXXX` 는 `standard_conforming_strings = on`(기본값)이라 문자열에서 그대로 정규식으로 넘어간다. 식은 `data.sql` 의 `-- R-A2 정규화` 로 끝나는 줄 한 곳에 두고 O35 가 그 줄을 읽어 벡터와 견준다 |
| 다시 적재 | 시드는 `NOT EXISTS` 로 이미 있는 행을 건너뛰어, 규칙이 바뀌어도 기존 행의 `answer_hash` 는 다시 쓰이지 않는다. D22 는 시드 답의 해시를 바꾸지 않으므로(위 `answer`) 할 일이 없다. 앞으로 해시가 바뀌는 변경을 넣으면: ① `UPDATE questions SET answer_hash = <같은 식>` 한 번(문제 id 가 그대로라 `served_questions` FK · 출제 이력이 남는다) 또는 로컬이면 `./iac/local.sh down` 뒤 볼륨 삭제(`docker volume rm <프로젝트>_postgres-data` — 데이터가 다 지워진다) ② `api` 재시작(문제 캐시 Q1 을 다시 읽는다) ③ 그날 미리 만든 은행(Redis — game-session LLD §3.6)은 옛 해시를 들고 있으니 지우거나 다음 날까지 둔다 |

## 3. 처리 절차
| 이벤트 | SQL |
|---|---|
| 07:00 명단 확정 (`worker: member`) | 그날 그룹원(06:50 전 가입 — prd D24 · 그룹원 2명 이상 그룹)마다 `INSERT INTO daily_progress (…, progress, status) VALUES (…, -1, 'NOT_STARTED') ON CONFLICT DO NOTHING` — 접속하지 않은 유저(-1)도 랭킹 · 결과 스냅샷에 들어간다 |
| 미션 시작 | `INSERT INTO daily_progress (…, progress, status) VALUES (…, 0, 'IN_PROGRESS') ON CONFLICT (game_date, user_id) DO UPDATE SET progress = GREATEST(daily_progress.progress, 0), status = CASE WHEN daily_progress.status = 'NOT_STARTED' THEN 'IN_PROGRESS' ELSE daily_progress.status END` |
| 풀이 반영 | `INSERT INTO solve_signals … ON CONFLICT DO NOTHING` → `UPDATE daily_progress SET progress = GREATEST(progress, :no), …` → `UPDATE served_questions SET kind = 'SOLVED' WHERE user_id = :u AND question_id = :q` |
| 진입 신호 | `INSERT INTO served_questions (kind='SHOWN') … ON CONFLICT DO NOTHING` — 충돌한 행의 (game_date · question_no)가 다르면 이미 낸 문제가 다시 나간 것이므로 운영 로그 `question.served_duplicate`(prd D15 · game-session HLD §4.1) |
| 패스 | `INSERT INTO served_questions (kind='PASSED') … ON CONFLICT (user_id, question_id) DO UPDATE SET kind='PASSED'` · `UPDATE daily_progress SET pass_question_no = :no WHERE pass_question_no IS NULL` |
| 부정 실패 | `UPDATE daily_progress SET status='CHEAT_FAILED', cheat_failed_at = :t WHERE status='IN_PROGRESS'` |
| 그룹 결과 (`group.decided`) | `INSERT INTO group_results … ON CONFLICT DO NOTHING` → 삽입됐을 때만 `group_result_members` 일괄 삽입(같은 트랜잭션 · 랭킹은 잠정일 수 있다) |
| 마감 실패 그룹원 (`group.decided` · `group.ranking_final` · result = `FAILED_DEADLINE`, 각자 같은 트랜잭션) | `UPDATE daily_progress SET status = 'FAILED' WHERE game_date = :d AND group_id = :g AND user_id = ANY(랭킹 s = FAILED 인 uid) AND status IN ('NOT_STARTED','IN_PROGRESS')` — 상태는 보통 `group.decided` 가 바꾸고 `group.ranking_final` 에서는 바꿀 행이 없다(같은 규칙을 두 번 — 앞 메시지를 건너뛰었어도 남는다) · `worker: deadline` 이 그룹 Hash 에서 바꾼 상태를 정본에도 남긴다(재적재가 `daily_progress` 로 같은 상태를 만든다 · prd D17) |
| 랭킹 확정 (`group.ranking_final`, 08:00:03) | ① `INSERT INTO group_results … ON CONFLICT DO NOTHING`(보통 이미 있다 — 앞 `group.decided` 를 형식 오류로 건너뛴 경우 FK 를 채운다 · `member_count` = 랭킹 줄 수) ② `group_result_members` 를 확정 랭킹으로 `INSERT … ON CONFLICT (game_date, user_id) DO UPDATE … WHERE (칸들) IS DISTINCT FROM EXCLUDED` — 08:00 확정 그룹의 유예 반영분을 덮는다(prd D17 · 같은 값이면 행을 바꾸지 않는다) ③ 위 마감 실패 그룹원 UPDATE — 한 트랜잭션 |
| 결과 캐시 (두 이벤트 공통, 커밋 뒤) | Redis `result:{<gid>}` 에 결과 · 랭킹 JSON 을 TTL 25시간(`EX 90000`)으로 덮어쓴다 — 캐시의 게임 날짜가 같거나 이르면 덮어쓰고(다음 게임 결과는 TTL 이 남아 있어도 덮는다), 더 늦으면 쓰지 않는다 · 같은 날 확정 랭킹을 잠정으로 덮지 않는다(Lua `result_cache_put` — deadline-result LLD §4.3). 커밋이 실패하면 캐시에 닿지 않는다. 캐시 쓰기 실패는 로그만 남기고 ACK 하되, 확정 랭킹은 키를 지우고 그것도 못 하면 ACK 하지 않는다(deadline-result LLD §3.4 4). 결과 조회의 cache-aside 캐시(deadline-result HLD §3) |

### 3.1 persister 소비 루프 (`StreamPersister` — T-PF-3)
처리기(위 표의 SQL)는 기능 일감이 `PersisterEventHandler` 빈으로 더한다. 이 절은 그 처리기를 부르는 루프다.

**처리기 계약**
- `types` 의 `type` 마다 처리기는 하나다 — 둘이면 기동 실패. 처리기가 없는 `type` 은 **처리기 실패와 같다(센다)** — WARN `persister.unhandled` 뒤 ACK 하지 않고 버킷을 멈춘다. PostgreSQL 에 쓰이기 전의 Stream 은 유일한 사본이라(`group.decided` 는 NFR-2 대상) 처리기가 붙기 전(생산자 일감이 먼저 머지 · 롤링 배포) 이벤트를 ACK · 트림으로 그냥 잃지 않는다. `dead-letter-after` 번 이어지면 아래 dead-letter 로 원본을 PostgreSQL 에 옮기고 넘어간다(prd D20 — 잃지는 않지만 정본 반영은 범위 밖의 재처리 몫이라, 생산자보다 처리기를 먼저 머지 · 배포한다). 그 전에 처리기가 붙어 재기동하면 그 메시지부터 처리된다.
- `handle` 이 **돌아오면 커밋된 것**이다(트랜잭션은 처리기 안에서 연다 — 저장소 포트가 `TransactionalOperator` 로). 결과 캐시 같은 커밋 뒤 일도 처리기 안에서 한다(deadline-result LLD §3.4).
- 예외 = 커밋 안 됨 → ACK 하지 않고 다시 준다(같은 메시지가 두 번 이상 올 수 있으니 SQL 은 멱등 — §4). `Error`(`TODO()` 등) · 처리기 안 타임아웃(`TimeoutCancellationException`)도 처리기 실패다 — 루프 자체가 멈출 때의 취소만 취소로 본다. 다시 줘도 고칠 수 없는 메시지(칸 형식 틀림)는 `MalformedEventException` 을 던진다 → ERROR `persister.malformed` 한 줄 뒤 **처음 실패에 바로** dead-letter · `XACK`(같은 칸은 몇 번을 읽어도 같은 결과라 3번을 기다리면 버킷만 늦는다 — T-PF-3 까지는 로그만 남기고 버렸다).

**맡는 버킷 · 레인**
1. 이 인스턴스가 맡는 버킷 = `b mod instance-count == instance-index` 인 `b`(0 ~ 1,023 — 하나면 전부). 소비자 이름 `worker-<instance-index>` — 재기동해도 같은 이름이라 자기 PEL 을 다시 읽는다. 인스턴스마다 index 를 다르게 준다(같으면 한 버킷을 두 소비자가 나눠 읽어 순서가 깨진다 — deadline-result 의 같은 이름 설정과 같은 규칙). 그래서 운영은 고정 수 · 순번 index(StatefulSet 순번 등)로 띄우고, 바꿀 때는 옛 인스턴스를 먼저 내린 뒤 새것을 띄운다 — 같은 index 가 겹쳐 뜨는 롤링 배포 · 자동 확장(HPA)은 쓰지 않는다(HLD §9).
2. 기동(`SmartLifecycle.start`) 때 맡은 버킷마다 `XGROUP CREATE ev:{b} persister 0 MKSTREAM`(있으면 그대로 — 그룹이 생기기 전의 메시지도 읽는다).
3. 맡은 버킷을 `parallelism` 개 레인(코루틴)에 나눠 준다(맡은 목록 순번 mod `parallelism`). **버킷 하나는 레인 하나만 돈다** — 한 버킷의 메시지는 Stream 순서대로 하나씩 처리된다.

**레인 한 바퀴** (맡은 버킷마다 차례로)
1. 회수: 기동 직후와 `claim-interval`(30초)마다 `XAUTOCLAIM ev:{b} persister <me> <claim-min-idle> 0-0 COUNT <batch>` 를 커서가 `0-0` 이 될 때까지 — 죽은 소비자(인스턴스 수를 바꾼 경우 등)가 ACK 하지 못한 메시지를 가져와 처리한다.
2. 자기 PEL: 기동 직후, 처리기가 실패한 버킷, Redis 오류가 난 버킷(읽기가 Redis 에서는 됐는데 응답을 잃었을 수 있다 · ACK 가 실패했을 수 있다)은 「밀린 것 있음」 상태다. 이 상태면 `XREADGROUP … <me> COUNT <batch> STREAMS ev:{b} 0`(내게 배달됐지만 ACK 안 한 것, id 순) — 빈 목록이 오면 상태를 푼다. 실패한 버킷은 `retry-backoff`(1초, 실패가 이어지면 두 배씩 `retry-backoff-max` 30초까지) 뒤에야 다시 읽는다.
3. 밀린 것이 없으면 `XREADGROUP … COUNT <batch> STREAMS ev:{b} >`(`BLOCK` 없음 — 공유 연결을 막지 않는다).
4. 읽은 메시지를 id 순서대로 처리한다. 처리기가 실패하면 **그 메시지에서 멈춘다** — 뒤 메시지는 처리하지 않고(같은 버킷의 순서를 지킨다) 버킷을 「밀린 것 있음」으로 둔다. 로그 WARN `persister.handler_failed`(bucket · id · type · 연속 실패 수 · 셌는지 `counted`) — 연속 10번째부터는 ERROR `persister.stuck`(멈춘 버킷은 트림도 멈춰 Stream 이 자란다 — 운영 경보 대상). 같은 메시지의 **센** 실패가 `dead-letter-after`(3)에 닿으면 멈추지 않고 dead-letter 로 넘긴 뒤 다음 메시지를 처리한다(아래). 그래서 버킷이 오래 서는 것은 세지 않는 실패(DB · Redis 장애)뿐이다.
5. 처리한(커밋 · 건너뜀 · 지워진 항목 · dead-letter 로 쌓음) id 를 모아 `persister_ack` 한 번: `XACK` → 트림 `XTRIM ev:{b} MINID <m>`(`m` = 이번 마지막 id 와 `XPENDING` 요약의 가장 작은 id 중 작은 것 — ACK 안 한 메시지는 지우지 않는다). 처리 뒤 ACK 전에 죽으면 다시 받아 멱등 SQL 로 같은 결과다.
6. 한 바퀴 동안 어느 버킷에서도 읽은 것이 없으면 `idle-delay`(200ms) 쉰다. Redis 오류는 WARN `persister.redis_failed` 뒤 **그 버킷만** 「밀린 것 있음」 · 회수 다시 · 그룹 다시 만들기(Redis 가 비었을 수 있다)로 두고 `retry-backoff`(Redis 오류 연속 수로 두 배씩) 뒤 다시 본다 — 레인의 다른 버킷은 그대로 돈다. 오류 없이 한 걸음을 마치면 Redis 오류 수를 0 으로 되돌린다(처리기 실패 수와 따로 센다).

**dead-letter** (T-PF-6 · prd D20 — 다시 넣는 처리 프로세스 · 조회 API · 운영 도구 · 경보는 **범위 밖**)
1. **센 실패**: 처리기 예외 중 아래 일시 장애가 아닌 것 · 처리기 없는 `type` · `MalformedEventException`(바로 문턱으로). **세지 않는 실패**(일시 장애): 예외와 그 원인 사슬의 어느 클래스 계층에 `org.springframework.dao.DataAccessResourceFailureException`(커넥션 획득 실패 · R2DBC 비일시 자원 오류 · `RedisConnectionFailureException` 이 그 아래다) · `org.springframework.dao.TransientDataAccessException`(쿼리 타임아웃 · 일시 자원 오류 · 잠금 · 직렬화 실패) · `org.springframework.dao.RecoverableDataAccessException` · `io.r2dbc.spi.R2dbcTransientException` · `io.r2dbc.spi.R2dbcNonTransientResourceException` · `java.sql.SQLTransientException` · `java.sql.SQLRecoverableException` · `java.io.IOException` · `java.util.concurrent.TimeoutException` · `kotlinx.coroutines.TimeoutCancellationException` · `RetryableEventException`(처리기 계약 — 커밋은 됐는데 커밋 뒤 일(결과 캐시)을 못 해 다시 받기를 바랄 때. `SignalEventHandlers` 의 확정 랭킹 캐시 실패) 이 있거나, 원인 사슬의 R2DBC 예외(`io.r2dbc.spi.R2dbcException`)의 SQLSTATE 가 `08`(연결) · `40`(직렬화 · 교착) · `53`(자원 — `53300` 커넥션 고갈) · `57`(운영자 개입 — `57P01` 종료 · `57P03` 기동 중) · `58`(시스템) 계열이거나 `25006`(장애 조치 뒤 읽기 전용) 이면 세지 않는다 — r2dbc-postgresql 은 `08` 만 자원 예외로 바꾸고 나머지는 일반 예외라 Spring 이 `UncategorizedR2dbcException` 으로 감싸기 때문이다. 세지 않는 실패는 T-PF-3 그대로 backoff 재시도를 끝없이 하고 10번째부터 `persister.stuck`.
   - 근거: 이것들은 메시지 내용이 아니라 DB · Redis · 네트워크 상태로 나는 실패다. backoff 1초 · 2초로 세 번이면 약 3초인데 PostgreSQL 재시작 · 장애 조치는 수십 초라, 세면 장애 동안 버킷마다 머리 메시지(1,024 버킷이면 최대 1,024건의 정상 이벤트)가 정본에 들어가지 못하고 dead-letter 로 간다.
   - 버린 대안: 모든 예외를 센다(위 문제) · 센 예외만 나열(처리기가 목록에 없는 예외를 던지면 버킷이 영원히 선다 — D20 이 막으려던 것).
   - 한계: `IOException` 은 넓다 — 처리기가 JSON 파싱 오류(`JsonProcessingException` 은 `IOException` 아래)를 감싸지 않고 던지면 세지 않아 버킷이 선다. 처리기는 칸 오류를 `MalformedEventException` 으로 바꿔 던진다(처리기 계약). 데이터 때문에 나는 타임아웃(느린 쿼리)도 세지 않아 그 버킷은 여전히 선다(`persister.stuck` 경보). FK · 제약 위반(`DataIntegrityViolationException`)은 센다 — 다시 해도 안 되는 실패라 맞다.
2. **세는 단위는 메시지(id)** 다. 버킷 상태에 (id · 센 수 · 처음 실패 시각 · 마지막 사유)를 두고, 다른 id 가 실패하면 새로 센다. 그 메시지를 처리하거나 dead-letter 로 쌓으면 지운다. 메모리 값이라 재기동하면 0 부터 다시 센다(재기동이 끼면 3번보다 더 시도할 수 있다 — 덜 시도하지는 않는다). 시각은 `Clock` 빈(앱 시계 — 게임 시각 주입 `game.clock-offset` 은 넣지 않는다).
3. 센 수가 `dead-letter-after` 에 닿으면 **같은 처리 흐름에서** `DeadLetterStore.put` → 돌아오면 그 id 를 처리된 것으로 모아 ACK(레인 5) 하고 같은 배치의 다음 메시지로 이어 간다. 버킷의 처리기 실패 수 · backoff 는 그 자리에서 0 으로 돌아간다(같은 배치의 다음 메시지가 실패해도 backoff 는 처음부터). 로그 ERROR `persister.dead_lettered`(bucket · id · type · failures · reason).
4. `put` 이 실패하면(예외 — 메시지 잘못이 아니므로 dead-letter 로 보내지 않는다) **ACK 하지 않는다**: 앞서 처리한 것만 ACK 하고 그 메시지에서 멈춰 backoff 뒤 자기 PEL 부터(처리기 실패와 같은 길). WARN `persister.dead_letter_failed`. 센 수는 그대로라 다음 시도에서 처리기가 또 센 실패를 내면 곧바로 다시 `put` 한다 — 처리기가 그사이 성공하면 그대로 처리된다(유실 0).
5. SQL(한 문장 — 그 자체가 트랜잭션):
   ```sql
   INSERT INTO persister_dead_letters (bucket, stream_id, event_type, fields, failure_count, last_error, first_failed_at, last_failed_at)
   VALUES (:b, :id, :type, jsonb_object(CAST(:keys AS text[]), CAST(:values AS text[])), :n, :error, :first, :last)
   ON CONFLICT (bucket, stream_id) DO UPDATE SET
       failure_count   = GREATEST(persister_dead_letters.failure_count, EXCLUDED.failure_count),
       last_error      = CASE WHEN EXCLUDED.last_failed_at >= persister_dead_letters.last_failed_at
                              THEN EXCLUDED.last_error ELSE persister_dead_letters.last_error END,
       first_failed_at = LEAST(persister_dead_letters.first_failed_at, EXCLUDED.first_failed_at),
       last_failed_at  = GREATEST(persister_dead_letters.last_failed_at, EXCLUDED.last_failed_at)
   ```
   쌓은 뒤 ACK 전에 죽거나 ACK 가 실패하면 같은 메시지를 다시 받아 다시 센 뒤 다시 쌓는다 — (bucket, stream_id) PK 라 **한 행** 그대로다(`fields` · `event_type` 은 같은 메시지라 바꾸지 않는다). `last_error` 는 앞 2,000자이고 `last_failed_at` 과 같은 시도의 것이다. PostgreSQL 이 받지 못하는 NUL(`U+0000`)은 칸 이름 · 값 · 사유에서 `\u0000` 여섯 글자로 바꿔 넣는다 — 이상한 메시지일수록 dead-letter 로 가야 하는데 쓰기가 늘 실패하면 버킷이 선다. 같은 까닭으로 `event_type` 은 길이 제한 없는 TEXT 다(`stream_id` 는 Redis 가 만든 id 라 41자 안).
6. 순서: dead-letter 로 넘긴 메시지 뒤의 같은 버킷 메시지는 그 메시지 없이 처리된다 — D20 이 고른 것(버킷이 서는 것보다 낫다). 뒤 처리기의 SQL 은 앞 이벤트가 없어도 멱등 · 조건부라(§3 표 — `GREATEST` · 상태 조건 · `group.ranking_final` 이 `group_results` 를 채움) 행이 깨지지 않는다.
7. dead-letter 로 ACK 한 메시지는 pending 이 아니라 트림(레인 5)이 지운다 — 그 뒤 사본은 `persister_dead_letters` 에만 있다.

**멈출 때** (`SmartLifecycle.stop`) 레인을 취소하고 기다린다 — 처리 중이던 메시지는 ACK 되지 않아 재기동 때 2 로 다시 처리된다. 이미 커밋한 것의 ACK 는 취소돼도 하되 5초로 제한한다(Redis 장애 중 종료가 막히지 않게 — 못 하면 다시 받아 멱등 SQL 로 같은 결과).

- 한계: 인스턴스 수를 바꾸면 버킷 주인이 바뀌어 옛 소비자의 pending 을 1 이 30초 뒤에 가져온다 — 그 사이 새 메시지가 먼저 처리될 수 있다(멱등 SQL 이라 행은 같고, `GREATEST` 로 진행도는 줄지 않는다).
- `worker.persister.enabled=false` 면 루프를 띄우지 않는다(잡만 시험할 때).

## 4. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| 같은 이벤트를 `worker: persister` 가 두 번 받음 | 자연키 PK + `ON CONFLICT DO NOTHING` · `GREATEST` | 결과 같음 |
| 그룹 결과 이벤트 중복 | `group_results` 삽입이 됐을 때만 `group_result_members` 삽입 | 결과 같음 |
| 다음 날 파티션 없음 | `worker: member` 가 전날 미리 생성 | — |
| 빈 DB 에서 `api` · `worker` 가 함께 뜸 · `api` 여러 대가 함께 뜸 | 스키마 DDL 은 advisory lock 한 줄로 차례를 선다(§4.3). `worker` 는 compose 에서 `api` healthy 뒤에 뜬다 | `api` 가 deadlock · 카탈로그 중복으로 죽지 않는다 |
| Redis 가 비어 재시작 · 게임 중 Redis 비움 | 명단을 만드는 길(`RosterService` · `worker: member`)이 그날 `daily_progress` · `solve_signals` · `served_questions` · `group_results` 로 그룹 Hash 를 다시 올린다(§4.2) | 첫 신호는 `NO_ROSTER` → 재적재 → 다시 불러 정상 |
| persister 가 한 메시지를 처리하지 못함(처리기 없는 `type` · 처리기 버그 · 형식 오류) | `dead-letter-after`(3)번 센 실패 뒤(형식 오류는 처음에) `persister_dead_letters` 에 한 행 · ACK · 다음 메시지(§3.1 dead-letter · prd D20) | — |
| dead-letter 쓰기 중 DB 장애 | ACK 하지 않고 backoff 뒤 다시 읽는다 | 유실 0 |

- 인덱스 `group_results_group_idx` · `group_result_members_group_idx` 는 결과 조회(deadline-result LLD §2.3)가 쓴다 — 그룹의 가장 최근 결과 · 그날 그룹 랭킹을 파티션 전체 탐색 없이 찾는다.

### 4.1 처리하지 못한 예외의 오류 응답 (prd D21 · T-PF-7)
기능 코드가 `BusinessException` 으로 바꾸지 않은 예외는 지금까지 Spring 기본 500(본문이 `{ code, message }` 가 아님)으로 나갔다. 앱마다 `FallbackExceptionHandler`(`WebExceptionHandler`) 하나가 받아 아래 절차로 응답한다.

**절차** (`FallbackExceptionHandler.handle`)
1. 응답이 이미 시작됐으면(`isCommitted` — SSE 200 뒤 스트림 중 끊김 등) 손대지 않고 넘긴다. 그 경우는 realtime-board LLD §4.4 · I8(T-RB-6)이 정한다. 클라이언트가 끊은 요청(`DisconnectedClientHelper.isClientDisconnectedException`)도 넘긴다 — 써 줄 곳이 없고 서버 결함이 아니라 ERROR 로그를 남기지 않는다(Boot 처리기와 같은 걸러내기).
2. 4xx `ResponseStatusException`(Spring 이 상태를 정한 요청 오류 — 없는 경로 404 · 405 · 406 · 컨트롤러 밖 입력 오류)은 넘긴다 → Boot 기본 처리. 맞는 `ErrorCode` 가 없고, 서버 결함이 아니다. 5xx(`ServerErrorException` — 인자 해석 실패 등)는 넘기지 않고 3 으로 간다(500).
3. 코드 = `FailureClassifier.errorCodeOf(ex)`. `BusinessException`(WebFilter 에서 던진 것 포함)은 그 코드 · 메시지, 나머지는 아래 표로 `UNAVAILABLE` 또는 `INTERNAL_ERROR` 이고 `message` 는 `ErrorCode.defaultMessage`(고정 문장)다 — 예외 메시지를 싣지 않는다(SQL · 호스트 · 스크립트 본문이 들어 있을 수 있다).
4. `BusinessException` 이 아니면 ERROR 로그 `http.unavailable` · `http.internal_error` 한 줄(메서드 · 경로 · 예외 스택). 경로에 쿼리는 넣지 않는다 — SSE 티켓이 로그에 남지 않게.
5. 상태 = `ErrorCode.httpStatus`, `Content-Type: application/json`, 본문 `ErrorResponse(code, message)` 를 앱의 `JsonMapper` 로 쓴다.

컨트롤러 안의 `BusinessException` · 입력 오류(`ServerWebInputException` 등)는 지금처럼 `GlobalExceptionHandler`(`@RestControllerAdvice`)가 먼저 처리한다. `@RestControllerAdvice` 에 「그 밖의 모든 예외」를 두지 않는 까닭: WebFilter(`AccessTokenWebFilter` · `SseConnectionReleaseFilter`)에서 난 예외는 거기 닿지 않고, `Exception` 을 잡으면 Spring 의 404 · 405 까지 500 으로 바뀐다.

**503 `UNAVAILABLE` 로 보는 예외** — 예외 자신과 원인 사슬(`cause` — 순환은 끊는다)의 어느 하나가 아래 타입이거나 그 하위 타입이면 503, 아니면 500.

| 타입 | 무엇 | 근거 |
|---|---|---|
| `DependencyUnavailableException`(common) | 우리 코드가 알리는 의존 서비스 장애. `GroupChannelLostException`(그룹 채널 구독 실패 · 끊김 · `SSUBSCRIBE` 확인 5초 초과 — `RedisGroupEventSubscriber`) | SSE 연결 때 구독 실패는 Redis 사정이다 |
| `org.springframework.dao.DataAccessResourceFailureException` | 자원 연결 실패 — `RedisConnectionFailureException`(Lettuce 연결 실패를 Spring 이 바꾼 것) · R2DBC 연결 획득 실패(`Failed to obtain R2DBC Connection`) | 하위 타입까지 본다 |
| `org.springframework.dao.TransientDataAccessException` | 다시 하면 될 수 있는 실패 — `QueryTimeoutException`(Redis 명령 시간 초과) · R2DBC 일시 오류 · 잠금 획득 실패 | Spring 이 「재시도 가능」으로 분류한 것 |
| `io.lettuce.core.RedisConnectionException` · `io.lettuce.core.RedisCommandTimeoutException` | Lettuce 연결 실패 · 명령 시간 초과(바꾸지 않은 채 오거나 `RedisSystemException` 안에 싸여 온 것) | `RedisSystemException` 은 Lua 스크립트 오류도 싸므로 그 자체로는 503 이 아니다 — 원인이 연결 계열일 때만 |
| `io.lettuce.core.RedisLoadingException` · `RedisReadOnlyException` · `RedisBusyException` | 재시작 뒤 AOF 읽는 중(LOADING) · 장애 조치 직후 replica 에 쓰기(READONLY) · 오래 도는 스크립트(BUSY) | `RedisCommandExecutionException` 하위지만 Redis 사정이라 잠시 뒤 낫는다. Spring 은 `RedisSystemException` 으로 싸서 준다 |
| `io.lettuce.core.RedisException` **그 타입 자체**(하위 타입 제외) | Lettuce 연결 상태 오류 — 「Connection closed」 · 「Currently not connected」 · 요청 큐 넘침 | 명령 오류(Lua · `WRONGTYPE`)는 하위 타입 `RedisCommandExecutionException` 이라 500 으로 남는다 |
| `io.r2dbc.spi.R2dbcNonTransientResourceException` · `io.r2dbc.spi.R2dbcTransientException` | R2DBC 연결 · 자원 실패, 일시 오류(시간 초과 · 롤백 포함) | Spring 이 바꾸기 전 드라이버 예외 |
| `java.net.ConnectException` | 연결 거부 · 연결 시간 초과(Netty `ConnectTimeoutException` 포함) | 드라이버가 싸지 않은 채 오는 경우 |

- 500 으로 남는 대표: `RedisSystemException`(원인이 `RedisCommandExecutionException` — Lua 오류 · `WRONGTYPE`), `BadSqlGrammarException` · `R2dbcBadGrammarException`, `DataIntegrityViolationException`, 그룹 Hash 파서의 `IllegalArgumentException`(realtime-board LLD §4.1 4), `NullPointerException` 등 서버 결함. 다시 해도 낫지 않으니 클라이언트가 재시도하지 않게 500 이다.
- 그룹 채널 구독이 실패하면 `GroupChannelHub` 는 원인이 무엇이든 `GroupChannelLostException` 으로 싸므로, 구독 코드의 서버 결함(설정 오류 · `check` 실패)도 503 이 된다 — 클라이언트가 재연결을 되풀이할 수 있다. 허브의 감싸기를 `FailureClassifier.isUnavailable` 일 때로 좁히는 일은 허브를 고치는 T-RB-6 과 겹쳐 이번에 하지 않았다(다음 일감에 넘김).
- `TransientDataAccessException` 아래에는 잠금 · deadlock · 직렬화 실패(재시도로 나을 수 있어 503 이 맞다)와 `TransientDataAccessResourceException` 이 있다. 지금은 `DatabaseClient` 만 써서 문제가 없지만, Spring Data repository 의 `save()` 는 「Row with Id … does not exist」 같은 결함도 이 타입으로 내므로 repository 를 들이면 이 줄을 다시 본다.
- 외부 타입을 이름으로 보는 까닭: `support:common` 은 Spring 에 기대지 않고(아키텍처 규칙), 앱 모듈은 `spring-tx` · Lettuce · R2DBC SPI 를 컴파일 의존으로 갖지 않는다(infra 가 `implementation` 으로 숨긴다). 이름 오타는 ORACLE E1 이 실제 타입으로 판정한다.
- 클라이언트 대응(api-guide §4): 503 은 잠시 뒤 재시도(신호 API 는 같은 본문 — 멱등, SSE 는 새 티켓 — RB7 ③), 500 은 재시도하지 않고 안내.
- SSE(`GET /realtime/v1/games/today/events`): 티켓 `GETDEL` 뒤 구독(`GroupChannelHub.attach`) · 현황 읽기(`snapshot`)에서 실패하면 컨트롤러가 연결을 돌려준 뒤 예외를 그대로 던지고, 응답 전이라 이 절차가 JSON 으로 바꾼다. 티켓은 이미 썼다 — 클라이언트는 새 티켓을 받는다. 컨트롤러 본문은 바꾸지 않는다.
### 4.2 재적재 (rehydrate — T-PF-5)
Redis 가 비면(재시작 뒤 AOF 없음 · `FLUSHALL` · 장애 조치로 빈 노드) 그날의 명단 Hash · 그룹 Hash · 그날 그룹 목록이 함께 사라진다(같은 해시태그 — 같은 슬롯). Lua 는 명단이 없으면 신호를 받지 않고 `NO_ROSTER` 를 돌려준다(game-session · solve-signal Lua 0단계) — 그래서 재적재 전에 그룹 Hash 를 바꾸는 실행은 없다. 재적재는 이 빈 자리를 PostgreSQL 의 그날 기록으로 다시 채운다.

**발동 — 「비었음」을 따로 알리지 않고, 명단을 만드는 길이 늘 재적재를 겸한다**
1. **요청 경로(`api`)**: Lua `NO_ROSTER` → `RosterService.findOrCreate(gid, D)`(game-session §3.4)가 명단(Q4 · Q6)과 함께 R1 · R2 로 그날 상태를 읽어 `roster.lua` 한 번에 명단 · 그룹 Hash · 그날 그룹 목록을 올린다 → 신호를 한 번 다시 부른다. 문턱은 그대로다 — 07:00 ≤ 지금 < 08:00(SignalService · GameService). 그래서 게임 중 Redis 가 비어도 그룹의 첫 요청 하나가 그 그룹을 되살리고, 유저는 오류 없이 이어 간다.
2. **`worker: member`**: `run(D)` 의 페이지마다 같은 방식으로 올린다(R1 · R2 를 페이지 묶음으로 한 번씩). 기록이 규칙 밖인 그룹(`GroupRestore` 거부)은 그 그룹만 건너뛰고 실패로 센다(ERROR `member.rehydrate_bad` — 페이지의 다른 그룹은 올린다). 그리고 **감시**: `run(D)` 는 시작할 때 버킷 표시 `rk:{<b>}:<D>` 를 1,024개 버킷마다 쓰고(값 `1` · 만료는 명단과 같은 다음 날 08:00), 끝에서 다시 센다 — 실행 도중 Redis 가 비었으면 표시가 빠져 있으므로 그 실행을 실패로 본다. 건너뛴 페이지 · 그룹 · 따라잡기 실패가 있어도 실패다(루프 · 감시가 다시 돈다). 루프는 다음 07:00 을 기다리는 동안 `worker.member.watch-interval`(10초)마다 그 날짜의 표시를 센다. 이 프로세스가 그 날짜 표시를 **본 뒤**(직접 썼거나 기동 때 다 있었거나) 하나라도 없어지면 Redis 를 잃은 것으로 보고 WARN `member.redis_lost date missing` 뒤 `run(D)` 를 다시 돈다(실패하면 다음 감시가 또 돈다). 표시를 본 적이 없으면 돌지 않는다 — 08:00 뒤 기동 때는 `run` 을 하지 않는다는 game-session §3.5 규칙(늦게 올린 그룹에 결과를 만들지 않는다)을 지킨다.
   - 감시 창: `D 07:00` 실행이 끝난 뒤부터 `D+1 07:00`(다음 실행)까지. 기동 때는 지금이 속한 게임 날짜(`startAt(d) ≤ 지금 < startAt(d+1)`)의 표시를 본다.
   - 요청 경로만으로는 모자라다: 08:00 마감은 `day:{<b>}:<D>` 를 돈다(deadline-result §3.2) — Redis 가 빈 뒤 요청이 없던 그룹은 목록에서 빠져 결과가 생기지 않는다(NFR-2). 기동 때만으로도 모자라다: Redis 만 재시작되고 앱은 그대로일 수 있다.
3. **마감 따라잡기(`worker: member` 만)**: 지금 ≥ `D 08:00` 이면 재적재 판정이 참인(기록이 있는) 2명 이상 그룹 **모두** 에 Lua `deadline`(1차 — `t ≥ 08:00:03` 이면 Lua 가 확정까지 한다)을 부른다(`GroupDayRepository.runDeadline`) — 이번 호출이 만들었는지(`created`)와 상관없다. 페이지를 다시 돌거나 앞 실행의 따라잡기가 실패했어도 빠지지 않게 하려는 것이고, Lua `deadline` 이 멱등이라(이미 처리된 그룹은 `SKIPPED`) 겹쳐도 같다. `SKIPPED` 가 아닌 결과만 「따라잡음」으로 센다. 끝내 실패하면 그 실행을 실패로 세어 표시를 쓰지 않는다. 결과 없는 그룹은 확정(`group.decided`) · 미룸(`pending`), 결과 있는 그룹은 08:00:03 뒤면 랭킹 확정(`group.ranking_final`). 08:00 · 08:00:03 의 마감 잡이 그 그룹을 이미 지나간 뒤라서다. `api` 경로는 08:00 전에만 재적재하므로 마감 잡이 그 그룹을 목록에서 찾는다.

**재적재 판정** — R1 에 그 그룹의 행이 하나라도 있거나 R2 에 결과가 있으면 「전에 명단이 있었다」(Q10 의 -1 행은 명단을 만든 뒤에만 들어간다 — game-session §3.4-3 · §3.5-4). 아니면 처음 만드는 명단이라 지금과 같이 `version 0` · 그룹원 -1 · `NOT_STARTED` 다(07:00 정상 실행).

**칸 매핑** (그룹 Hash 칸 정본 game-session LLD §2.2 · 시각은 µs 십진 문자열)
| 그룹 Hash 칸 | 원천 | 규칙 |
|---|---|---|
| `date` · `gid` · `size` · `<uid>:lid` | 명단(Q4 · Q6) | `roster` 와 같다 |
| `<uid>:p` | `daily_progress.progress` · `solve_signals` 의 가장 큰 `question_no` | 둘 중 큰 값(같은 트랜잭션에서 쓰므로 보통 같다). 행이 없으면 `-1` |
| `<uid>:st` | `daily_progress.status` | 그대로. `solve_signals` 로 진행도를 올려 3 이 됐고 `NOT_STARTED` · `IN_PROGRESS` 면 `SUCCEEDED`(persister 와 같은 규칙). 행이 없으면 `NOT_STARTED` |
| `<uid>:sa` · `<uid>:late` | `started_at` · `is_late` | `NULL` 이면 칸 없음(시작 전). `late` 는 `1` · `0` |
| `<uid>:ls` | `last_solved_at`(진행도를 `solve_signals` 로 올렸으면 그 행의 `arrived_at`) | `NULL` 이면 칸 없음 |
| `<uid>:pn` · `<uid>:pq` | `pass_question_no` · `passed_question_id` → `questions.pk_hash` | `NULL` 이면 칸 없음 |
| `<uid>:cur` | `served_questions` 중 `game_date = D` · `kind = SHOWN` · `question_no = p + 1` 인 행(`served_at` 가 가장 늦은 것)의 `pk_hash` | 패스로 넘긴 문제(`PASSED`) · 푼 문제(`SOLVED`)는 되살리지 않는다(shown 6 의 `pn` · `pq` 규칙과 같다). 없으면 칸 없음 |
| `<uid>:cf` | `cheat_failed_at` | `NULL` 이면 칸 없음 |
| `result` · `decidedAt` | `group_results.result` · `completed_at` | 행이 없으면 칸 없음 |
| `ranking` | 되살린 그룹원 칸으로 `roster.lua` 가 다시 계산(`decide.ranking`) | `result` 가 있을 때만. `group_result_members` 는 결과를 낸 순간의 스냅샷이라 쓰지 않는다 — 그 뒤의 유예 풀이 · 다시 계산을 놓친다 |
| `rankingFinal` | `group_results.completed_at` | `result` 가 있을 때만. `decidedAt < D 08:00`(조기 확정 — Lua `decide.run` 이 그 자리에서 `1` 로 쓴다)이면 `1`, 아니면(마감 확정) `0` — 08:00:03 의 랭킹 확정은 마감 따라잡기 · 마감 잡이 Lua `deadline` 으로 다시 낸다(PostgreSQL 에는 「랭킹 확정됨」 표시가 없다) |
| `pending` | — | 칸 없음. 08:00 ~ 08:00:03 이면 마감 따라잡기 · 마감 잡이 같은 규칙으로 다시 미룬다 |
| `version` | 재적재 때 앱 게임 시각(`GameClock.now()`)의 epoch **ms** | 아래 |

- 명단에 없는 `daily_progress` 행(그날 06:50 부터 가입 — prd D24, 운영상 없음)은 버린다. 명단에 있는데 행이 없는 그룹원은 `-1` · `NOT_STARTED` 그대로다.
- **`version`**: 옛 값은 PostgreSQL 에 없다(이벤트의 `version` 은 기록하지 않는다). 열린 SSE 연결은 `version ≤ lastVersion` 인 메시지를 버리므로(realtime-board LLD §3) 옛 값보다 작게 올리면 현황판이 멈춘다. 그래서 epoch ms(13자리 ≈ 1.8×10¹²)를 쓴다 — 그룹 하루 변경 수(그룹원 10명 × 몇 번 + 마감 몇 번 = 백 단위)보다 훨씬 커 FLUSH 전 어떤 값보다 크고, 두 번째 재적재의 값도 첫 재적재 뒤 늘어난 수(경과 ms 보다 작다)보다 크다. µs 는 쓰지 않는다 — 채널 메시지(`common.publish`)의 cjson 이 숫자를 14자리로 줄여 16자리 µs 를 잃는다. 재적재 자체는 publish 하지 않는다 — 다음 변경이 큰 `version` 으로 나가고, 새 연결은 스냅샷을 받는다.

**`roster.lua` 꼬리** (game-session LLD §4.2) — 명단 ARGV 뒤에 `[6+2n]` `version` · `[7+2n]` `rerank`(`0` · `1`) · `[8+2n]` m · 이어서 m 쌍의 (칸, 값). 꼬리가 없으면(`#ARGV = 5 + 2n`) 예전과 같다(`version 0`). 그룹 Hash 를 **만들 때만** 기본 칸 → 덮을 칸 → `version` 순으로 쓰고, `rerank = 1` 이면 `ranking` 을 다시 계산해 쓴다. 칸 이름은 `<명단의 uid>:<lid 가 아닌 칸>` 이거나 `result` · `decidedAt` · `rankingFinal` 만 받고, 값도 칸마다 검사한다(`p` −1 ~ 3 · `sa` · `ls` · `cf` · `decidedAt` 십진 정수 · `late` · `rankingFinal` `0` · `1` · `pn` 1 ~ 3 · `st` 상태 5개) — 그 밖이면 **쓰기 전에** 스크립트 오류다(랭킹 다시 계산이 쓰기 뒤에 실패해 만료 없는 반쪽 Hash 가 남지 않게). `GroupRestore` 도 「진행도 ≥ 1 이면 `ls` 있음」을 검사한다.

**동시성** — 재적재는 `roster.lua` 한 번이다: 명단이 없을 때만 명단 · 그룹 Hash · 그날 그룹 목록을 한 번에 만든다. 여러 `api` 인스턴스와 `worker` 가 같은 그룹을 동시에 올려도 하나만 `created = 1` 이고 나머지는 이미 있는 것을 돌려받는다. 늦게 읽은 PostgreSQL 값이 먼저 올린 그룹을 덮는 일도 없다 — 먼저 만든 쪽 뒤의 신호는 Redis 에 쌓이고, 늦은 쪽은 아무것도 쓰지 않는다. 마감 따라잡기는 `worker` 가 기록이 있는 그룹 모두에 부르지만 Lua `deadline` 이 멱등이라 여러 번 불러도 한 번만 바뀐다.

**오래된 데이터 — persister 가 아직 못 쓴 메시지** — Stream `ev:{<b>}` 도 같은 Redis 에 있다. Redis 가 통째로 비면 Stream 도 없으므로 PostgreSQL 에 아직 쓰이지 않은 반영(보통 수 ms ~ 수백 ms 분)은 되살리지 못한다 — AOF `everysec` 의 허용 유실(NFR-5 · 최대 1초)과 같은 선으로 다룬다. 유저에게는 그 신호가 반영 전으로 보이고, 같은 신호를 다시 보내면 정상 반영된다(이미 반영된 번호는 멱등 `REPLAY`). persister 가 멈춰 밀려 있었다면(`persister.stuck`) 그만큼 더 잃는다 — 운영 경보 대상이다. Redis 가 AOF 로 다시 뜬 경우(ORACLE O4)는 명단이 남아 있어 재적재가 돌지 않는다. 재적재 뒤의 새 이벤트는 새 Stream 에 쌓이고, persister 는 Redis 오류 뒤 소비자 그룹을 다시 만든다(§3.1 레인 6).

**되살리지 못하는 것 (한계)**
- persister 가 쓰기 전의 반영(위).
- 출제 중복 — `served_questions` 는 평생 키 (user_id, question_id) 라 다른 날 이미 낸 문제를 다시 냈으면 처음 기록(다른 날짜 · 번호)만 남는다(game-session §3.7). 그날 그 번호의 `cur` 가 없으므로 그 유저는 문제 화면에 다시 들어와 진입 신호로 다시 고정해야 하고(shown 6 — 그 전의 풀이는 `QUESTION_MISMATCH`), 08:00 미룸 판정에서 「3번 지금 문제 있음」으로 세지 않는다. 드문 운영 이상이다.
- `pending` · `rankingFinal` · `version` 의 옛 값 — 위 표의 규칙으로 다시 만든다. 마감 확정 그룹이 이미 랭킹 확정(08:00:03)까지 마쳤었다면 마감 따라잡기가 `group.ranking_final` 을 한 번 더 내지만 persister 의 upsert 는 같은 값이면 행을 바꾸지 않는다.
- 08:00 ~ 08:00:03 의 유예 신호 — `api` 는 08:00 뒤 명단을 만들지 않으므로(solve-signal `SignalService`) 그 사이 Redis 가 비면 `worker` 감시가 올릴 때까지 `MISSION_NOT_STARTED` 다. 감시 주기(10초)가 유예(3초)보다 길어 그 유예 신호는 사실상 받지 못한다.
- 결과 캐시 `result:{<gid>}` · 문제은행 `bank:` · `sess:` · `tk:` 는 재적재하지 않는다 — 결과 캐시는 PostgreSQL 로 다시 채워지고(cache-aside), 은행은 대체 경로(game-session §3.2-3)가 만들고, 로그인 · 실시간 티켓은 다시 받는다.
- 08:00 뒤 Redis 와 `worker` 가 **함께** 재시작되면(호스트 재부팅) 그 프로세스는 표시를 본 적이 없어 감시가 돌지 않고, 마감 잡의 재기동 재실행도 그날 그룹 목록이 비어 아무것도 못 한다 — 그 시점에 결과가 PostgreSQL 에 없던 그룹(마감 처리 전 · persister 가 못 씀)은 결과가 없다. 결과 조회는 PostgreSQL 에 있는 결과를 그대로 준다. 기동 때 표시가 없어도 R1 기록이 있는 그룹만 올리는 「재적재 전용 실행」으로 메울 수 있으나 이번 판에서는 하지 않는다(남은 판단거리).
- `api` 경로의 08:00 문턱은 앱 시각이다 — Lua 시각은 07:59:59.99 라 `NO_ROSTER` 인데 앱 시각이 08:00 을 넘은 ms 창에서 `api` 가 재적재하면 따라잡기가 없다(마감 잡이 그 버킷을 이미 훑었다면 `worker` 감시도 표시가 남아 있어 돌지 않는다). Redis 를 잃는 것과 08:00 경계 ms 가 겹치는 경우라 받아들인다.
- 미리 읽은 값 — `worker` 는 페이지의 R1 을 읽은 뒤 `roster` 를 부르기까지(페이지 처리 시간, 초 단위) 사이에 반영되고 FLUSH 된 신호를 놓친다(그 그룹은 그보다 옛 상태로 선다). 그 창에 Redis 를 잃는 경우라 받아들인다.
- Cluster 의 한 샤드만 잃으면 그 샤드 버킷의 표시만 없어진다 — 감시는 `run(D)` 전체를 다시 돈다(남은 명단은 `roster` 1 에서 건너뛴다 — 비용은 PostgreSQL 읽기).

**쿼리** (`RdbDailyProgressStore.loadDayStates` — 그룹 1개 또는 한 페이지)
| id | SQL |
|---|---|
| R1 | `SELECT dp.group_id, dp.user_id, dp.progress, dp.status, dp.started_at, dp.is_late, dp.last_solved_at, dp.pass_question_no, pq.pk_hash AS passed_pk, dp.cheat_failed_at, ss.question_no AS ss_no, ss.arrived_at AS ss_at, cur.pk_hash AS cur_pk FROM daily_progress dp LEFT JOIN questions pq ON pq.id = dp.passed_question_id LEFT JOIN LATERAL (SELECT question_no, arrived_at FROM solve_signals s WHERE s.game_date = dp.game_date AND s.user_id = dp.user_id ORDER BY question_no DESC LIMIT 1) ss ON true LEFT JOIN LATERAL (SELECT q.pk_hash FROM served_questions sq JOIN questions q ON q.id = sq.question_id WHERE sq.user_id = dp.user_id AND sq.game_date = dp.game_date AND sq.kind = 'SHOWN' AND sq.question_no = GREATEST(dp.progress, COALESCE(ss.question_no, -1)) + 1 ORDER BY sq.served_at DESC LIMIT 1) cur ON true WHERE dp.game_date = $1 AND dp.group_id = ANY($2::bigint[])` — `daily_progress_group_idx` · `solve_signals` PK · `served_questions` PK 앞부분(user_id) |
| R2 | `SELECT group_id, result, completed_at FROM group_results WHERE game_date = $1 AND group_id = ANY($2::bigint[])` — PK |

- 비용: 07:00 정상 실행도 페이지(500 그룹)마다 R1 · R2 를 한 번씩 더 읽는다. 07:00 에는 행이 거의 없어(-1 행은 이 실행이 넣는다) 인덱스 범위 탐색 한 번이다. 재적재가 몰리는 경우(게임 중 Redis 비움)는 그룹의 첫 요청마다 R1 · R2 한 번 — 신호 경로가 PostgreSQL 에 닿는 유일한 경우다.

### 4.3 기동 경합 — 스키마 잠금 (T-PF-12)
**증상**(프론트 종단 테스트가 찾음 · 두 번에 한 번꼴): 빈 볼륨으로 compose 를 띄우면 `api` 의 `schema.sql` 적용과 `worker: member` 의 기동 파티션 생성(Q9)이 겹쳐 PostgreSQL 이 deadlock(`40P01`)으로 한쪽을 죽인다. 죽는 쪽이 `api` 면 「Application run failed」로 끝나 스키마가 반쯤만 남고, `worker` 는 `relation "solve_signals" does not exist` 를 되풀이한다.

**원인 — DDL 두 문장이 같은 테이블들을 거꾸로 잠근다**
| 문장 | 잠그는 차례 |
|---|---|
| `api` `CREATE TABLE solve_signals (… REFERENCES questions(id), FOREIGN KEY … REFERENCES daily_progress …)` | FK 의 참조 테이블 `questions` → `daily_progress`(트리거를 달려고 `SHARE ROW EXCLUSIVE`) |
| `worker` `CREATE TABLE daily_progress_<D> PARTITION OF daily_progress …` | 부모 `daily_progress`(`ACCESS EXCLUSIVE`) → 부모에서 복제한 FK 의 참조 테이블 `users` · `groups` · `questions` |

`schema.sql` 끝의 오늘 · 내일 파티션 `DO` 블록과 `COMMENT ON` 도 같은 테이블들을 잠그므로 경합은 한 문장만의 일이 아니다. `api` 가 둘 이상 함께 뜨면 `CREATE TABLE IF NOT EXISTS` 끼리도 카탈로그 유일 인덱스(`pg_type_typname_nsp_index`)에서 `23505` 로 부딪힌다 — `IF NOT EXISTS` 는 이미 커밋된 것만 보기 때문이다(O36 테스트가 둘 다 재현했다).

**결정 — DDL 을 한 잠금 아래로(①) + compose 기동 순서(②)**
1. **스키마 잠금**: PostgreSQL advisory lock 키 하나(`SchemaLock.KEY` = `1296651091`)를 스키마를 바꾸는 모든 길이 먼저 잡는다.
   - `api` 의 `schema.sql` — 첫 문장 `SELECT pg_advisory_lock(1296651091)` · 마지막 문장 `SELECT pg_advisory_unlock(1296651091)`. Spring 의 스크립트 실행기(`ResourceDatabasePopulator`)는 한 파일을 한 연결에서 차례로 돌므로 세션 잠금이 파일 전체를 덮는다. `data.sql`(문제 시드)은 잠금 밖이다 — `NOT EXISTS` · `ON CONFLICT DO NOTHING` 이라 동시에 돌아도 같다(§2.2).
   - `worker` 의 Q9 `ensurePartitions(D)` — 테이블마다 `DO $$ BEGIN PERFORM pg_advisory_xact_lock(1296651091); CREATE TABLE IF NOT EXISTS <테이블>_<D> PARTITION OF <테이블> …; END $$` 한 문장씩 4번. 자동 커밋의 한 문장이 한 트랜잭션이라 잠금은 문장이 끝나면 풀린다. 잠금을 테이블보다 먼저 잡으므로 기다리는 동안 테이블 잠금을 쥐지 않는다.
   - 4개를 한 트랜잭션에 묶지 않는다(리뷰 반영): `PARTITION OF` 는 부모에 `ACCESS EXCLUSIVE` 를 잡으므로, 묶으면 `daily_progress` 를 쥔 채 `solve_signals` 를 기다리게 된다. persister 의 풀이 반영은 `solve_signals` → `daily_progress`, 결과 반영은 `group_results` → `group_result_members` → `daily_progress` 차례라 매일 07:00 의 `D+1` 생성(실제로 새로 만드는 유일한 때)과 순환할 수 있다. 한 문장에 부모 하나면 DML 과 순환할 짝이 없다(예전처럼 기다릴 뿐이다).
   - 그래서 스키마 DDL 은 「한 번에 한 길」이다 — 잠금 순서가 엇갈릴 상대가 없어 deadlock 이 생기지 않고, `api` 여러 대 · `worker` 여러 대가 함께 떠도 차례로 돈다. 늦게 온 `api` 는 이미 다 있는 것을 `IF NOT EXISTS` 로 지나간다.
   - 스키마가 아직 없을 때(`worker` 가 먼저 잠금을 잡음) Q9 는 `42P01`(relation does not exist)로 실패한다 — 기존 재시도(`MemberJob.retrying`)와 그 뒤의 `member.failed step=startup-partitions` 로그로 끝나고, 오늘 · 내일 파티션은 `api` 의 `schema.sql` 이 만든다. 막는 일은 아래 2 가 한다.
2. **compose 기동 순서**: `api` 에 헬스체크(`/health` 200 — 스키마 · 시드 적용이 끝나야 앱이 뜬다)를 두고 `worker` 는 `depends_on: api: service_healthy`. 로컬 · 종단 테스트에서는 `worker` 가 스키마가 다 된 뒤에 떠 1 의 `42P01` 도 보지 않는다. `realtime` 은 DDL 을 하지 않고 읽기만 해 순서를 바꾸지 않는다.

**버린 대안**
| 안 | 버린 까닭 |
|---|---|
| compose 순서만(②만) | 로컬 한 대에서는 경합이 사라지지만 `api` 여러 대(`23505`) · compose 밖 배포(쿠버네티스 등 — 순서 보장 없음) · `api` 재시작 중 `worker` 의 07:00 파티션 생성은 그대로 겹친다 |
| `api` 가 deadlock 이면 스키마 적용을 다시 시도 | 경합을 없애지 않고 덮는다. `spring.sql.init` 에 재시도가 없어 초기화기를 갈아 끼워야 하고, 반쯤 적용된 상태에서 다시 도는 것이 안전한지(오늘 파티션 `DO` 블록 · `COMMENT ON`)를 따로 따져야 한다 |
| 스키마 적용을 한 곳으로(마이그레이션 단계 · 초기화 컨테이너 · Flyway) | 가장 깔끔하지만 이번 범위(버그 수정)보다 크다 — 새 컨테이너 · 의존성 · `api` 의 `spring.sql.init` 과 테스트(테스트 컨텍스트가 schema.sql 로 스키마를 만든다) 모두를 옮겨야 한다. 잠금 키가 한 곳이라 나중에 옮겨도 Q9 쪽 잠금은 그대로 쓸 수 있다 |
| `worker` 가 스키마 준비를 따로 확인(테이블이 있는지 묻고 기다림) | `MemberJob` 을 고쳐야 하고(병렬 일감 T-GS-9 와 겹친다), 확인과 DDL 사이의 틈은 여전하다 — 잠금이 그 틈을 닫는다 |

**한계**
- 세션 잠금은 `schema.sql` 이 중간에 실패하면 그 연결이 풀에 잠금을 쥔 채 돌아간다. `spring.sql.init` 실패는 기동 실패라 앱이 끝나며 연결이 닫혀 풀린다(`continue-on-error` 를 켜지 않는다). 프로세스가 죽어도 PostgreSQL 이 연결을 닫을 때 푼다.
- 잠금은 DDL 끼리만 줄을 세운다. `worker: persister` 의 DML 은 잡지 않는다 — Q9 는 문장마다 부모 하나만 잠가 DML 과 엇갈리지 않고, 빈 DB 에서는 `api` 가 뜨기 전에 이벤트가 없고, 이미 스키마가 있는 DB 에서 `schema.sql` 의 문장은 `IF NOT EXISTS` 로 지나가거나 `COMMENT ON`(`SHARE UPDATE EXCLUSIVE` — DML 의 `ROW EXCLUSIVE` 와 겨루지 않는다)이다.
- 키 `1296651091` 은 이 DB 에서 다른 용도로 쓰지 않는다. 바꾸면 `schema.sql` 과 `SchemaLock.KEY` 를 함께 바꾼다(O36 이 견준다).

### 4.4 로컬 데모 시계 · 시각 이동 (prd D25 · T-PF-13)
게임 시각 = 실제 시각 + `game.clock-offset`(기동 때 고정) + `shift`(런타임 이동량). `shift` 의 정본은 Redis 키 `demo:clock`(µs 십진 정수) 하나다. 설정 `game.demo.enabled=false`(앱 기본 — 운영)면 `DemoClockService` · `DevClockController` 빈이 없고 `shift` 는 0 이다 — 키를 읽지도 않는다.

1. **첫 이동량** (`DemoClockRules.first` — 빈 볼륨의 첫 기동). `r` = 실제 지금, `lead` = `game.demo.lead`(3분), `base` = `clock-offset`, `gap` = `start-time − join-cutoff`(10분).
   `r` 의 KST 날짜부터 하루씩 늘려 가며 첫 `D` 를 고른다: `total = D 07:00 − (r + lead)` 가 `gap` 이상이고 `total − base ≥ 0`. `shift = total − base`.
   - `total ≥ gap` 이면 게임 07:00 전에(실제 `D 07:00 − total` 전에) 가입한 유저의 `joined_at`(실제 시각)은 `D 06:50` 보다 앞이다 → 그날 명단(`joined_at < D 06:50` — prd D24)에 든다. `frontend/e2e/run.sh` 의 계산과 같은 규칙이다.
   - `shift ≥ 0` 이라 게임 시각은 실제 시각보다 늘 앞이거나 같다(뒤로 가지 않는다).
2. **세 앱이 같은 값을 쓰기** — 빈을 만들 때(모든 `SmartLifecycle.start` 전) `SET demo:clock <후보> NX` → `GET` → `shift` 에 반영. 먼저 쓴 앱의 값이 정본이고, 볼륨이 남아 있으면 그 값을 그대로 쓴다(재기동해도 게임 시각이 이어진다).
   그 뒤 `game.demo.sync-interval`(1초)마다 `GET` 해 **커졌을 때만** 반영하고 `demo-clock.shifted from=… to=… now=…` INFO 로그를 남긴다. 읽기 실패는 WARN 로그 뒤 다음 주기에 다시 — 값은 그대로 둔다.
   - **앞으로만 반영**: 앱의 `shift` 는 줄지 않는다. sync 가 `move` 직전의 옛 값을 읽고 늦게 반영하려 해도 버린다(이동 직후 1초 동안 시각이 뒤로 가는 창이 없다).
   - **키를 잃으면 다시 심는다**: 키가 없으면 이 앱 값을 `SET NX` 로, 이 앱 값보다 작으면(Redis 를 비운 뒤 재기동한 앱이 새 후보를 심음) `compareAndSet` 으로 이 앱 값을 다시 쓰고 ERROR 로그 `demo-clock.rejected_backward` — 세 앱 중 가장 앞선 값이 정본으로 남는다. 다시 심기 전에 막 뜬 앱은 1주기 동안 이른 시각으로 돌 수 있다(한계).
   앱의 「지금」(`GameProperties.now(clock)` · `GameClock.now()`)과 Lua ARGV `offsetUs`(`GameProperties.clockOffsetUs`)는 같은 `shift` 를 읽는다. 앱끼리는 최대 `sync-interval` 만큼 늦게 맞춰진다.
3. **시각 이동** (`DemoClockService.move` · `POST /api/v1/dev/clock`). `now` = 이 앱의 게임 지금, `target` = 옮길 게임 시각.
   - 프리셋 `start` = `now` 뒤 가장 이른 07:00 − 10초, `deadline` = `now` 뒤 가장 이른 08:00 − 10초. 또는 절대 시각 `at`.
   - `limit` = `now` 뒤 가장 이른 경계(그날 · 다음 날의 07:00 · 08:00 · 08:00:03) − `game.demo.move-margin`(5초). `now < target ≤ limit` 이 아니면 `409 CLOCK_MOVE_REJECTED`(SPEC §6.3).
   - 통과하면 `new = shift + (target − now)` 를 `compareAndSet(읽은 값 → new)` 로 쓴다. 다른 요청이 먼저 바꿨으면(값이 다름) 한 번 다시 읽고 검사부터 다시 한다(두 번째도 지면 409). 쓴 앱은 자기 `shift` 를 바로 바꾸고, 다른 앱은 2의 주기에 따라온다.
   - **세션 키 TTL 을 줄인다**: refresh token 만료(2시간)는 게임 시각으로 재지만 세션 키 `sess:{<uid>}` 의 TTL 은 실제 시간으로 흐른다. 옮긴 만큼 줄이지 않으면 하루를 옮긴 뒤 refresh 는 만료(401)인데 세션 키가 남아 다시 로그인이 `409 ALREADY_LOGGED_IN` 이 된다. 그래서 이동이 성공하면 모든 세션 키의 남은 TTL 을 이동량만큼 줄인다(남는 것이 없으면 지운다 — `shortenSessions`). access token(1시간)은 이동만큼 일찍 만료되고, 클라이언트는 평소처럼 refresh 한다.
   - **왜 앞으로만 · 경계 앞까지만**: Redis 만료는 실제 시간(`PX` — 남은 ms)으로 흐르고, 그룹 Hash `version` · 명단 · `day:` 키 · 결과는 되돌릴 수 없다 — 뒤로 가면 이미 만든 명단 · 마감 결과 위에 같은 날이 다시 열린다. 경계를 건너뛰면 `worker` 의 07:00 명단과 08:00 마감이 늦게 · 동시에 돌아 순서(명단 → 마감 1차 → 2차)가 깨질 수 있다. 경계 5초 전까지만 옮기면 경계는 언제나 실제 시간이 흘러 지나가고, 5초는 다른 앱이 `sync-interval`(1초) 안에 따라오는 틈보다 넉넉하다. 06:30 · 06:50 `prepare-questions` 는 건너뛰어도 된다 — 지난 실행은 바로 돌고, 못 만든 은행은 `api` 가 요청 때 만든다(D18).
   - 앞으로 옮기면 이미 쓴 Redis 키는 게임 시각으로 보면 그만큼 늦게 사라진다(만료가 늘어날 뿐 일찍 사라지지 않는다). 키에 날짜가 들어 있어 다음 게임과 섞이지 않는다.
4. **잡의 다음 실행 시각 다시 고르기** — `worker: member`(07:00) · `prepare-questions`(06:30 · 06:50) · `deadline`(08:00 · 08:00:03)의 기다림은 `GameClock.pauseUntil(at, 원래 상한)` 이다(`prepare-questions` 는 새 상한 1분). `pauseUntil` 은 `shift` 가 바뀌면 바로 돌아오고, 루프는 `GameClock.now()` 로 남은 시간을 다시 잰다 — 시각이 지났으면 곧바로 돈다. `prepare-questions` 는 원래 상한이 없어 남은 시간 전체를 기다렸는데, 이제 이동에도 깬다. 운영(꺼짐)에서는 `shift` 가 바뀌지 않아 기다림은 예전과 같다.
5. **한계** (데모 전용이라 받아들인다)
   - `users.joined_at` 은 실제 시각(DB `now()` — auth-signup AS15)이고 명단 기준 `D 06:50` 은 게임 시각이다. 이동 총량이 10분보다 크면, 게임 07:00 **뒤**에 가입해 새 그룹을 만든 유저도 그 그룹의 명단이 요청 때 만들어지면(game-session §3.4) 그날 그룹원이 된다(prd D24 와 다름). 07:00 `worker: member` 가 이미 명단을 만든 그룹은 영향이 없다. 가입 응답 `firstGameDate` 도 실제 날짜로 계산돼 이동 뒤에는 게임 날짜와 어긋날 수 있다. 버린 대안: `joined_at` 을 앱이 게임 시각으로 넣기 — 운영 계약(AS15)을 바꾸는 일이라 이 일감 밖이다.
   - 하루를 옮기면 refresh token 도 만료돼 다시 로그인한다(세션 키는 위 3 이 줄여 두어 로그인이 막히지 않는다).
   - 이동 검사는 경계 시각만 본다 — 잡이 아직 돌고 있는지는 보지 않는다. 데모 규모(그룹 몇 개)에서는 07:00 명단 · 08:00 마감이 1초 안에 끝나 문제가 없다.
   - 한 요청 안에서 앱의 날짜 고르기와 Lua `offsetUs` 는 `shift` 를 따로 읽는다. 경계를 넘는 이동이 막혀 07:00 ~ 08:00:03 판정은 같고, 자정을 넘는 이동은 게임이 닫힌 구간이라 결과 코드가 같다.
   - 브라우저는 서버 시각을 게임 정보를 받을 때 맞추므로 이동 뒤 새로고침한다.

## 5. 구현 순서
0. 공통 코드 바탕(§1 의 `ErrorCode` · `GameProperties` · `RedisKeys` · `RedisScripts` · `lib/common.lua`) — ORACLE O8 ~ O12. 기능 Lua · 저장소는 모두 이 위에 선다
1. 스키마 DDL(`schema.sql` — §2.1 전체 · 인덱스 · 오늘 파티션 포함) · 문제 시드(`data.sql` — §2.2) — ORACLE O13 · O14
2. 하루 파티션 생성 잡
3. `app:worker` 모듈 뼈대(`settings.gradle.kts` · `WorkerApplication` · compose `worker` 서비스) + `worker: persister` 소비 루프 `StreamPersister`(§3.1 — `XREADGROUP` · 처리기 등록 · 커밋 뒤 `XACK` · `XAUTOCLAIM` · 트림) — ORACLE O15 ~ O18. 이벤트별 처리기(멱등 upsert SQL)는 각 기능 일감이 더한다(HLD §4.2 표)
3a. persister dead-letter(T-PF-6 · prd D20) — `persister_dead_letters` DDL · 포트 `DeadLetterStore` · `RdbDeadLetterStore` · `StreamPersister` 의 실패 세기 · 설정 `dead-letter-after` — ORACLE O16 · O19 ~ O21
4. 재적재(rehydrate — §4.2): R1 · R2(`DailyProgressStore.loadDayStates`) · 덮을 칸 계산(`GroupRestore`) · `roster.lua` 꼬리 · `RosterService` · `worker: member` 페이지 재적재 · 버킷 표시 감시 · 마감 따라잡기 — ORACLE O22 ~ O26
5. 처리하지 못한 예외의 오류 응답(§4.1 — `ErrorCode` 2개 · `DependencyUnavailableException` · `FailureClassifier` · 앱마다 `FallbackExceptionHandler`) — ORACLE E1 ~ E4(T-PF-7)
6. 워크트리 격리(T-PF-10 — §1 `iac/compose.yaml` · `iac/local.sh`) — ORACLE O31 ~ O33
7. 정답 정규화(T-PF-11 · prd D22 — §1 `AnswerNormalizer` · §2.2 시드 SQL · 공유 벡터) — ORACLE O14 · O34 · O35
8. 기동 경합(T-PF-12 — §4.3 스키마 잠금 · compose `api` 헬스체크 · `worker` 의존) — ORACLE O36 ~ O38
9. 로컬 데모 시계(T-PF-13 — §4.4: `ClockShift` · `DemoClockRules` · `DemoClockStore` · `DemoClockService` · `GameClock.pauseUntil` 로 잡 3개의 기다림 · `DevClockController` · compose 환경 변수) — ORACLE O39 ~ O46
