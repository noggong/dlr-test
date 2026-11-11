# LLD — platform (DB 설계)

> 판 v5.7 · 2026-11-11 (v5.7: T-DR-6 — 랭킹 확정(`group.ranking_final`)은 `group_results` 가 없으면 넣고 마감 실패 그룹원 `daily_progress FAILED` 도 같은 트랜잭션에서 · 결과 캐시 Lua 는 읽지 못하는 값을 덮는다 · v5.6: `worker` 는 `spring.main.keep-alive=true` — 웹 서버가 없고 루프 스레드가 데몬이라 그대로면 기동 직후 프로세스가 끝난다(로컬 기동에서 발견) · v5.5: T-PF-3 리뷰 반영 — 처리기 없는 `type` 은 ACK 하지 않고 버킷을 멈춤(처리기가 붙기 전 이벤트를 잃지 않게) · Redis 오류 뒤에도 자기 PEL 부터 · 처리기의 `Error` · 타임아웃도 처리기 실패로 · 연속 실패 10번부터 ERROR `persister.stuck` · `batch-size` ≤ 1,000 · 멈출 때 ACK 5초 제한 · 고정 index 운영 규칙 · v5.4: T-PF-3 구현 반영 — `app:worker` 뼈대 · persister 소비 루프 `StreamPersister` 의 절차(§3.1 — 버킷 나눔 · 레인 · 처리기 계약 · 실패 시 버킷 멈춤 · 자기 PEL 재읽기 · `XAUTOCLAIM` · ACK 와 트림) · 포트 `EventStreamConsumer` · Lua `persister_*` · 설정 `worker.persister.*` · v5.3: T-PF-1 리뷰 반영 — 기동 때 KST 오늘 · 내일 파티션(`worker: member` 전에 자정을 넘겨도 쓰기가 막히지 않게) · 시드가 이미 있으면 시퀀스를 쓰지 않음 · 시드 답의 문자 범위 · v5.2: T-PF-1 구현 반영 — `schema.sql` 전체를 `IF NOT EXISTS` 꼴로 · 하루 파티션은 날짜를 박지 않고 기동 때 KST 날짜로 만드는 `DO` 블록 · 문제 시드 `data.sql`(생성 규칙 · §2.2) · `daily_progress.status` 설명을 도메인 `board.MemberStatus` 로 · v5.1: 리뷰 반영 — lib 검사는 기동 때 · 반환의 nil/false · 환경 변수 이름 · `ErrorCode` 는 그대로 · v5: 기능들이 함께 쓰는 공통 코드 바탕 — `ErrorCode` · `GameProperties.clockOffset` · `bucketCount` · `RedisKeys` · `RedisScripts` · `lua/lib/common.lua` 와 lib 모듈 규약(§1 · §5 0단계 · 일감 T-PF-2) · v4: 마감 실패 그룹원 FAILED 기록 · v3: 통합 — 스키마 DDL 경로 `backend/infra/rdb/src/main/resources/schema.sql`(auth-signup LLD) · 결과 조회 인덱스 2개(deadline-result LLD §2.3) · `app:worker` 모듈 뼈대 · persister 소비 루프 `StreamPersister` 를 이 문서의 일감으로 · `group_result_members.cheat_failed` 설명을 API 계약(`status`)에 맞춤 · v2: prd v6 결정 D17(상태 FAILED · CHEAT_FAILED · 랭킹 확정) · prd v4 결정 D6 · D7 · D8 · D9 · D11 · D12 를 컬럼 설명에 · 로컬 실행 파일을 `iac/` 실제 경로로 · 상태 값을 도메인 · API 와 같게 · 결과 표시 상태를 prd D4 로 · 결과 스냅샷에 D5 필드 · 07:00 명단 확정 절차) · 짝 문서: [HLD](../hld/platform.md) · [SPEC](../spec/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) v10 · 원본 산출물: `docs/outputs/feature-infra-arch/db/`

**세 줄 요약**
- 바뀌는 것: PostgreSQL 스키마(테이블 8개 · 하루 파티션)와 `worker: persister` 의 멱등 쓰기 규칙.
- 데이터 모델: 마스터(`groups` · `users` · `questions` · `served_questions`) + `game_date` 파티션(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`). CHECK 없이 백엔드가 검증하고 허용 값은 `COMMENT ON` 에 둔다.
- 구현 순서: 스키마 → 파티션 생성(`worker: member`) → `worker: persister` 멱등 upsert → 재적재(rehydrate).

## 1. 모듈 · 파일
| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `backend/infra/rdb/src/main/resources/schema.sql` | 수정 | 스키마 DDL — 아래 §2.1 그대로(`CREATE … IF NOT EXISTS` 꼴로 써서 `spring.sql.init.mode=always` 로 `api` 기동 때마다 적용 — 몇 번 적용해도 같다). auth-signup 일감이 `groups` · `users` 로 처음 만들고(LLD auth-signup §1), 나머지 테이블 6개 · 인덱스 · 오늘 파티션은 §5 1단계(T-PF-1)에서 더했다 |
| `backend/infra/rdb/src/main/resources/data.sql` | 새로 | 문제 시드(§2.2) — Spring 기본 데이터 스크립트 자리라 `schema.sql` 바로 뒤에 같은 기동에서 적용된다. `INSERT … SELECT generate_series … ON CONFLICT (pk_hash) DO NOTHING` 이라 다시 적용해도 행이 늘지 않는다 |
| `backend/support/common/…/common/error/ErrorCode.kt` | (그대로) | [api-guide](../api-guide.md) §4 표의 코드 21개를 같은 순서로 이미 다 갖고 있다(기능 LLD 들이 「먼저 하는 쪽이 더한다」고 적은 `ALREADY_LOGGED_IN` · `NOT_PARTICIPANT` · `QUESTIONS_EXHAUSTED` · `QUESTION_MISMATCH` 포함). 표와 enum 을 견주는 계약 테스트만 더한다(ORACLE O12 — 표만 바뀌어도 다시 돈다) |
| `backend/core/domain/…/core/config/GameProperties.kt` | 수정 | `clockOffset: Duration = 0`(설정 `game.clock-offset` — 시각 주입, HLD §8) · `bucketCount = 1024`. 계산 함수 `now(clock)` = `clock.instant() + clockOffset`(앱이 「지금」을 고를 때 쓰는 하나뿐인 길 — `Clock` 빈 자체는 오프셋을 모른다) · `clockOffsetUs`(Lua ARGV `offsetUs` — µs 아래는 내림) · `bucketOf(gid)` = `gid mod bucketCount`. 오프셋이 µs 로 넘치면 기동 때 실패한다. 환경 변수로 줄 때 이름은 `GAME_CLOCKOFFSET` · `GAME_BUCKETCOUNT` 다(`GAME_CLOCK_OFFSET` 은 `game.clock.offset` 으로 읽혀 무시된다). `api` · `realtime` · `worker` 는 같은 값을 가져야 시각 · 키가 맞는다. 기능 LLD 가 더하는 수치(`lateGrace` 등)는 그 일감이 더한다 |
| `backend/infra/redis/…/infra/redis/RedisKeys.kt` | 새로 | 버킷 · 키 이름 함수 — HLD §4.1 표의 키를 글자 그대로 만든다: `group(gid, date)` `g:{<b>}:<gid>:<date>` · `members(gid, date)` · `dayGroups(b, date)` · `events(b)` · `channel(gid)` · `ticket(t)` · `session(uid)` · `result(gid)` · `bank(uid, date)`. `<date>` 는 `YYYY-MM-DD`, 버킷은 `GameProperties.bucketOf`. 빈으로 등록한다(`RedisInfraAutoConfiguration`) |
| `backend/infra/redis/…/infra/redis/RedisScripts.kt` | 새로 | Lua 로더 — 하나만 둔다. `eval(name, keys, args)`: `lua/lib/*.lua` 를 모두 앞에 붙인 `lua/<name>.lua` 를 처음 쓸 때 `SCRIPT LOAD` 하고 `EVALSHA`. lib 는 빈을 만들 때 읽고 검사한다(규약 위반은 기동 실패). `NOSCRIPT`(Redis 재시작 · `SCRIPT FLUSH` · 장애 조치)면 다시 올리고 **1회만** 재시도한다. 반환은 목록(문자열 칸은 `String`, 정수 칸은 `Long`, 안쪽 배열은 `List`) — 스칼라 반환은 한 칸 목록이다. Lua 표 안의 `nil` 은 배열을 그 자리에서 자르고(조용히 짧아진다) `false` 는 RESP nil 이라 로더가 오류로 끝낸다 — 빈 칸은 `''` 로 돌려주고, 쓰는 쪽은 칸 수를 확인한다. 빈으로 등록한다 |
| `backend/infra/redis/src/main/resources/lua/lib/common.lua` | 새로 | `common.now(offsetUs)` = Redis `TIME` 의 `초 × 1e6 + µs` + `offsetUs`(`-?숫자` 꼴의 십진 정수가 아니면 — `1e3` · `0x10` · 빈 값 포함 — 스크립트 오류). 그 밖의 도우미(그룹 상태 · 그룹원 칸 · XADD · SPUBLISH)는 solve-signal 일감이 더한다(LLD solve-signal §1) |
| `backend/settings.gradle.kts` · `backend/app/worker/build.gradle.kts` · `…/app/worker/WorkerApplication.kt` | 새로 | 세 번째 앱 모듈 `app:worker`(HLD §5) 뼈대 — 웹 서버 없이(`spring.main.web-application-type: none`) `@EnableScheduling`. 붙들 웹 서버 스레드가 없어 `spring.main.keep-alive: true` 로 프로세스를 살려 둔다. 기능 일감(`worker: member` · `prepare-questions` · `deadline` · persister 처리기)은 이 모듈에 잡 · 처리기만 더한다 |
| `backend/app/worker/…/app/worker/persister/StreamPersister.kt` | 새로 | `worker: persister` 소비 루프 — 버킷 Stream `ev:{<b>}` 을 consumer group `persister` 로 `XREADGROUP`, `type` 별 처리기(기능 LLD 가 정함 — HLD §4.2 표) 호출, 커밋 뒤 `XACK`, 기동 때와 30초마다 `XAUTOCLAIM`(idle 30초). 버킷 하나는 한 번에 한 소비자만 처리해 순서를 지킨다. 절차는 §3.1 |
| `backend/app/worker/…/app/worker/persister/PersisterEventHandler.kt` | 새로 | 처리기 계약(§3.1) — `types: Set<String>` · `suspend handle(event: StreamEvent)`(돌아오면 커밋됨). 고칠 수 없는 메시지는 `MalformedEventException`. 기능 일감은 이 인터페이스의 빈을 `persister/` 에 더하기만 한다 |
| `backend/app/worker/…/app/worker/persister/PersisterProperties.kt` | 새로 | 설정 `worker.persister.*` — `enabled`(true) · `instance-index`(0) · `instance-count`(1) · `parallelism`(16) · `batch-size`(100 · 1 ~ 1,000 — `persister_ack` 의 `unpack` 한도) · `idle-delay`(200ms) · `claim-interval`(30s) · `claim-min-idle`(30s) · `retry-backoff`(1s) · `retry-backoff-max`(30s) |
| `backend/core/domain/…/core/domain/event/StreamEvent.kt` | 새로 | 이벤트 Stream 메시지 한 건 — `id`(Stream id) · `fields`(HLD §4.2 의 칸 그대로, 문자열) · `type`. 지워진 항목(트림 뒤 PEL 에만 남은 id)은 `fields` 가 비어 있다 |
| `backend/core/domain/…/core/port/EventStreamConsumer.kt` | 새로 | persister 소비 포트(consumer group `persister` 고정) — `ensureGroup(b)` · `readNew(b, consumer, count)` · `readOwnPending(b, consumer, count)` · `claimIdle(b, consumer, minIdle, count, cursor)`(`XAUTOCLAIM` 한 번 — 커서를 돌려준다) · `ack(b, ids)`. 앱은 infra 를 직접 쓰지 않는다(아키텍처 규칙) |
| `backend/infra/redis/…/infra/redis/RedisEventStreamConsumer.kt` (+ `lua/persister_group.lua` · `persister_read.lua` · `persister_claim.lua` · `persister_ack.lua`) | 새로 | 포트 구현 — Spring Data Redis 에 `XAUTOCLAIM` 이 없어 Stream 명령을 모두 `RedisScripts` Lua 로 돈다(`BLOCK` 없음). `persister_ack` 는 `XACK` 과 트림(`XTRIM MINID` — 이번 마지막 id 와 그룹의 가장 작은 pending id 중 작은 것)을 한 번에 한다 |
| `compose.yaml` (루트) | 새로 | `iac/compose.yaml` 을 include — 루트에서 `docker compose up` 한 번 |
| `iac/compose.yaml` | 새로 · 수정 | `redis` · `postgres` · `api` · `realtime` · `worker` · `web` ([HLD](../hld/platform.md) §8). `worker` 는 §5 3단계(T-PF-3)에서 더했다 — 웹 서버가 없어 헬스 주소 대신 컨테이너가 `running` 인지 본다 |
| `iac/docker/backend.Dockerfile` (+ `.dockerignore`) | 새로 · 수정 | `backend/` 를 빌드해 타깃 `api` · `realtime` · `worker` 이미지 |
| `iac/docker/frontend.Dockerfile` · `default.conf` (+ `.dockerignore`) | 새로 | `frontend/` 정적 빌드 → nginx(SPA 경로는 `index.html`) |
| `iac/local.sh` | 새로 · 수정 | 띄우기 · 헬스 확인(+ `worker` 가 `running`) · 내리기 ([SPEC](../spec/platform.md) §3) |

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

- **CHECK 제약은 두지 않는다.** 값 범위 · 허용 값 검증은 모두 백엔드가 한다 — 규칙이 바뀔 때(정원 · 상태 값 · 문제 번호 범위) 마이그레이션 없이 확장하기 위해서다. 허용 값 · 의미는 DDL 의 `COMMENT ON` 에 남긴다(검증 규칙의 정본). PK · UNIQUE · FK · NOT NULL 은 정합성(NFR-2 · NFR-3)의 마지막 방어선이라 남긴다.
- 하루 단위 테이블(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`)은 `game_date` 범위 파티션 — 오래된 날은 파티션째 보관 · 삭제.
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
COMMENT ON COLUMN groups.member_count IS '현재 소속 인원. 허용 값 0~10 — 10 이면 11번째 가입을 「그룹이 꽉 찼다」로 거부(R-G3). 증가는 백엔드가 조건부 UPDATE(member_count < 10)로 원자 처리. 07:00 이후 가입자도 센다(prd D12).';
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
COMMENT ON COLUMN users.joined_at     IS '가입 시각. 그 날짜 07:00 KST 이전이면 그날 그룹원(R-G4 · R-G5), 이후면 다음 날부터.';

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
COMMENT ON COLUMN questions.answer_hash IS '정답 해시 — 띄어쓰기 전부 제거 → 소문자 → 앞뒤 공백 제거 후 해시(R-A2). 값 = SHA-256(UTF-8("<pk_hash>:<정규화한 답>")) 소문자 hex(prd D7).';

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
COMMENT ON COLUMN group_results.member_count IS '그날의 그룹원 수(07:00 이전 가입자). 허용 값 2~10.';
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
| `answer` | `Mission <d>-<nnn> 정답`(`nnn` = 0 채운 세 자리) — 대문자 · 공백이 있어 정규화(R-A2)를 거쳐야 맞는다. 문자는 ASCII 영숫자 · `-` · U+0020 · 한글만 쓴다 — SQL(`[[:space:]]` · `lower`)과 Kotlin(`isWhitespace` · `lowercase`)의 정규화가 이 범위에서는 같다(U+00A0 · U+3000 같은 공백이나 ASCII 밖 대소문자의 정확한 규칙은 R-A2 를 구현하는 game-session 일감이 정한다) |
| `body` | `[시드 <d>-<nnn> · <분야>] 다음 말을 그대로 입력하세요: <answer>` — 본문에 답이 보여 사람이 손으로 풀어 볼 수 있다 |
| `answer_hash` | `SHA-256(UTF-8("<pk_hash>:<정규화한 answer>"))` 소문자 hex(prd D7). 정규화 = 공백 문자 전부 제거(`[[:space:]]`) → 소문자(앞뒤 공백 제거는 앞 단계로 이미 끝났다) |

## 3. 처리 절차
| 이벤트 | SQL |
|---|---|
| 07:00 명단 확정 (`worker: member`) | 그날 그룹원(07:00 이전 가입 · 그룹원 2명 이상 그룹)마다 `INSERT INTO daily_progress (…, progress, status) VALUES (…, -1, 'NOT_STARTED') ON CONFLICT DO NOTHING` — 접속하지 않은 유저(-1)도 랭킹 · 결과 스냅샷에 들어간다 |
| 미션 시작 | `INSERT INTO daily_progress (…, progress, status) VALUES (…, 0, 'IN_PROGRESS') ON CONFLICT (game_date, user_id) DO UPDATE SET progress = GREATEST(daily_progress.progress, 0), status = CASE WHEN daily_progress.status = 'NOT_STARTED' THEN 'IN_PROGRESS' ELSE daily_progress.status END` |
| 풀이 반영 | `INSERT INTO solve_signals … ON CONFLICT DO NOTHING` → `UPDATE daily_progress SET progress = GREATEST(progress, :no), …` → `UPDATE served_questions SET kind = 'SOLVED' WHERE user_id = :u AND question_id = :q` |
| 진입 신호 | `INSERT INTO served_questions (kind='SHOWN') … ON CONFLICT DO NOTHING` — 충돌한 행의 (game_date · question_no)가 다르면 이미 낸 문제가 다시 나간 것이므로 운영 로그 `question.served_duplicate`(prd D15 · game-session HLD §4.1) |
| 패스 | `INSERT INTO served_questions (kind='PASSED') … ON CONFLICT (user_id, question_id) DO UPDATE SET kind='PASSED'` · `UPDATE daily_progress SET pass_question_no = :no WHERE pass_question_no IS NULL` |
| 부정 실패 | `UPDATE daily_progress SET status='CHEAT_FAILED', cheat_failed_at = :t WHERE status='IN_PROGRESS'` |
| 그룹 결과 (`group.decided`) | `INSERT INTO group_results … ON CONFLICT DO NOTHING` → 삽입됐을 때만 `group_result_members` 일괄 삽입(같은 트랜잭션 · 랭킹은 잠정일 수 있다) |
| 마감 실패 그룹원 (`group.decided` · `group.ranking_final` · result = `FAILED_DEADLINE`, 각자 같은 트랜잭션) | `UPDATE daily_progress SET status = 'FAILED' WHERE game_date = :d AND group_id = :g AND user_id = ANY(랭킹 s = FAILED 인 uid) AND status IN ('NOT_STARTED','IN_PROGRESS')` — 상태는 보통 `group.decided` 가 바꾸고 `group.ranking_final` 에서는 바꿀 행이 없다(같은 규칙을 두 번 — 앞 메시지를 건너뛰었어도 남는다) · `worker: deadline` 이 그룹 Hash 에서 바꾼 상태를 정본에도 남긴다(재적재가 `daily_progress` 로 같은 상태를 만든다 · prd D17) |
| 랭킹 확정 (`group.ranking_final`, 08:00:03) | ① `INSERT INTO group_results … ON CONFLICT DO NOTHING`(보통 이미 있다 — 앞 `group.decided` 를 형식 오류로 건너뛴 경우 FK 를 채운다 · `member_count` = 랭킹 줄 수) ② `group_result_members` 를 확정 랭킹으로 `INSERT … ON CONFLICT (game_date, user_id) DO UPDATE … WHERE (칸들) IS DISTINCT FROM EXCLUDED` — 08:00 확정 그룹의 유예 반영분을 덮는다(prd D17 · 같은 값이면 행을 바꾸지 않는다) ③ 위 마감 실패 그룹원 UPDATE — 한 트랜잭션 |
| 결과 캐시 (두 이벤트 공통, 커밋 뒤) | Redis `result:{<gid>}` 에 결과 · 랭킹 JSON 을 TTL 25시간(`EX 90000`)으로 덮어쓴다 — 캐시의 게임 날짜가 같거나 이르면 덮어쓰고(다음 게임 결과는 TTL 이 남아 있어도 덮는다), 더 늦으면 쓰지 않는다 · 같은 날 확정 랭킹을 잠정으로 덮지 않는다(Lua `result_cache_put` — deadline-result LLD §4.3). 커밋이 실패하면 캐시에 닿지 않고, 캐시 쓰기 실패는 로그만 남기고 ACK 한다. 결과 조회의 cache-aside 캐시(deadline-result HLD §3) |

### 3.1 persister 소비 루프 (`StreamPersister` — T-PF-3)
처리기(위 표의 SQL)는 기능 일감이 `PersisterEventHandler` 빈으로 더한다. 이 절은 그 처리기를 부르는 루프다.

**처리기 계약**
- `types` 의 `type` 마다 처리기는 하나다 — 둘이면 기동 실패. 처리기가 없는 `type` 은 **처리기 실패와 같다** — WARN `persister.unhandled` 뒤 ACK 하지 않고 버킷을 멈춘다. PostgreSQL 에 쓰이기 전의 Stream 은 유일한 사본이라(`group.decided` 는 NFR-2 대상) 처리기가 붙기 전(생산자 일감이 먼저 머지 · 롤링 배포) 이벤트를 ACK · 트림으로 잃지 않는다. 처리기가 붙어 재기동하면 그 메시지부터 처리된다.
- `handle` 이 **돌아오면 커밋된 것**이다(트랜잭션은 처리기 안에서 연다 — 저장소 포트가 `TransactionalOperator` 로). 결과 캐시 같은 커밋 뒤 일도 처리기 안에서 한다(deadline-result LLD §3.4).
- 예외 = 커밋 안 됨 → ACK 하지 않고 다시 준다(같은 메시지가 두 번 이상 올 수 있으니 SQL 은 멱등 — §4). `Error`(`TODO()` 등) · 처리기 안 타임아웃(`TimeoutCancellationException`)도 처리기 실패다 — 루프 자체가 멈출 때의 취소만 취소로 본다. 다시 줘도 고칠 수 없는 메시지(칸 형식 틀림)는 `MalformedEventException` 을 던진다 → ERROR `persister.malformed` 한 줄 뒤 `XACK`.

**맡는 버킷 · 레인**
1. 이 인스턴스가 맡는 버킷 = `b mod instance-count == instance-index` 인 `b`(0 ~ 1,023 — 하나면 전부). 소비자 이름 `worker-<instance-index>` — 재기동해도 같은 이름이라 자기 PEL 을 다시 읽는다. 인스턴스마다 index 를 다르게 준다(같으면 한 버킷을 두 소비자가 나눠 읽어 순서가 깨진다 — deadline-result 의 같은 이름 설정과 같은 규칙). 그래서 운영은 고정 수 · 순번 index(StatefulSet 순번 등)로 띄우고, 바꿀 때는 옛 인스턴스를 먼저 내린 뒤 새것을 띄운다 — 같은 index 가 겹쳐 뜨는 롤링 배포 · 자동 확장(HPA)은 쓰지 않는다(HLD §9).
2. 기동(`SmartLifecycle.start`) 때 맡은 버킷마다 `XGROUP CREATE ev:{b} persister 0 MKSTREAM`(있으면 그대로 — 그룹이 생기기 전의 메시지도 읽는다).
3. 맡은 버킷을 `parallelism` 개 레인(코루틴)에 나눠 준다(맡은 목록 순번 mod `parallelism`). **버킷 하나는 레인 하나만 돈다** — 한 버킷의 메시지는 Stream 순서대로 하나씩 처리된다.

**레인 한 바퀴** (맡은 버킷마다 차례로)
1. 회수: 기동 직후와 `claim-interval`(30초)마다 `XAUTOCLAIM ev:{b} persister <me> <claim-min-idle> 0-0 COUNT <batch>` 를 커서가 `0-0` 이 될 때까지 — 죽은 소비자(인스턴스 수를 바꾼 경우 등)가 ACK 하지 못한 메시지를 가져와 처리한다.
2. 자기 PEL: 기동 직후, 처리기가 실패한 버킷, Redis 오류가 난 버킷(읽기가 Redis 에서는 됐는데 응답을 잃었을 수 있다 · ACK 가 실패했을 수 있다)은 「밀린 것 있음」 상태다. 이 상태면 `XREADGROUP … <me> COUNT <batch> STREAMS ev:{b} 0`(내게 배달됐지만 ACK 안 한 것, id 순) — 빈 목록이 오면 상태를 푼다. 실패한 버킷은 `retry-backoff`(1초, 실패가 이어지면 두 배씩 `retry-backoff-max` 30초까지) 뒤에야 다시 읽는다.
3. 밀린 것이 없으면 `XREADGROUP … COUNT <batch> STREAMS ev:{b} >`(`BLOCK` 없음 — 공유 연결을 막지 않는다).
4. 읽은 메시지를 id 순서대로 처리한다. 처리기가 실패하면 **그 메시지에서 멈춘다** — 뒤 메시지는 처리하지 않고(같은 버킷의 순서를 지킨다) 버킷을 「밀린 것 있음」으로 둔다. 로그 WARN `persister.handler_failed`(bucket · id · type · 연속 실패 수) — 연속 10번째부터는 ERROR `persister.stuck`(멈춘 버킷은 트림도 멈춰 Stream 이 자란다 — 운영 경보 대상).
5. 처리한(커밋 · 건너뜀 · 지워진 항목) id 를 모아 `persister_ack` 한 번: `XACK` → 트림 `XTRIM ev:{b} MINID <m>`(`m` = 이번 마지막 id 와 `XPENDING` 요약의 가장 작은 id 중 작은 것 — ACK 안 한 메시지는 지우지 않는다). 처리 뒤 ACK 전에 죽으면 다시 받아 멱등 SQL 로 같은 결과다.
6. 한 바퀴 동안 어느 버킷에서도 읽은 것이 없으면 `idle-delay`(200ms) 쉰다. Redis 오류는 WARN `persister.redis_failed` 뒤 **그 버킷만** 「밀린 것 있음」 · 회수 다시 · 그룹 다시 만들기(Redis 가 비었을 수 있다)로 두고 `retry-backoff`(Redis 오류 연속 수로 두 배씩) 뒤 다시 본다 — 레인의 다른 버킷은 그대로 돈다. 오류 없이 한 걸음을 마치면 Redis 오류 수를 0 으로 되돌린다(처리기 실패 수와 따로 센다).

**멈출 때** (`SmartLifecycle.stop`) 레인을 취소하고 기다린다 — 처리 중이던 메시지는 ACK 되지 않아 재기동 때 2 로 다시 처리된다. 이미 커밋한 것의 ACK 는 취소돼도 하되 5초로 제한한다(Redis 장애 중 종료가 막히지 않게 — 못 하면 다시 받아 멱등 SQL 로 같은 결과).

- 한계: 인스턴스 수를 바꾸면 버킷 주인이 바뀌어 옛 소비자의 pending 을 1 이 30초 뒤에 가져온다 — 그 사이 새 메시지가 먼저 처리될 수 있다(멱등 SQL 이라 행은 같고, `GREATEST` 로 진행도는 줄지 않는다).
- `worker.persister.enabled=false` 면 루프를 띄우지 않는다(잡만 시험할 때).

## 4. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| 같은 이벤트를 `worker: persister` 가 두 번 받음 | 자연키 PK + `ON CONFLICT DO NOTHING` · `GREATEST` | 결과 같음 |
| 그룹 결과 이벤트 중복 | `group_results` 삽입이 됐을 때만 `group_result_members` 삽입 | 결과 같음 |
| 다음 날 파티션 없음 | `worker: member` 가 전날 미리 생성 | — |
| Redis 가 비어 재시작 | 그날 `daily_progress` · `solve_signals` 로 그룹 Hash 재적재 | — |

- 인덱스 `group_results_group_idx` · `group_result_members_group_idx` 는 결과 조회(deadline-result LLD §2.3)가 쓴다 — 그룹의 가장 최근 결과 · 그날 그룹 랭킹을 파티션 전체 탐색 없이 찾는다.

## 5. 구현 순서
0. 공통 코드 바탕(§1 의 `ErrorCode` · `GameProperties` · `RedisKeys` · `RedisScripts` · `lib/common.lua`) — ORACLE O8 ~ O12. 기능 Lua · 저장소는 모두 이 위에 선다
1. 스키마 DDL(`schema.sql` — §2.1 전체 · 인덱스 · 오늘 파티션 포함) · 문제 시드(`data.sql` — §2.2) — ORACLE O13 · O14
2. 하루 파티션 생성 잡
3. `app:worker` 모듈 뼈대(`settings.gradle.kts` · `WorkerApplication` · compose `worker` 서비스) + `worker: persister` 소비 루프 `StreamPersister`(§3.1 — `XREADGROUP` · 처리기 등록 · 커밋 뒤 `XACK` · `XAUTOCLAIM` · 트림) — ORACLE O15 ~ O18. 이벤트별 처리기(멱등 upsert SQL)는 각 기능 일감이 더한다(HLD §4.2 표)
4. 재적재(rehydrate)
