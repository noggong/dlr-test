# 인프라 아키텍처 — 동시 접속 그룹 미션 서버

> 판 v7 · 2026-11-10 · 근거: `docs/prd.md` v2 (D1 반영) · 상태: 설계 단계(구현 전)

## 1. 결정 요약

| 영역 | 결정 | 버린 대안 |
|---|---|---|
| 언어 · 프레임워크 | Kotlin + Spring Boot (구현은 백엔드 워크트리) | Go · Node — 사람 결정 |
| 프로세스 | 코드 하나, 역할 분리 실행: `api`(:8080) · `sse`(:8081) · `worker` (Spring profile) | 서비스 완전 분리(과함) · 단일 프로세스(확장 축이 묶임) |
| 핫 상태 · 판정 | Redis — 그룹 단위 Hash + Lua 스크립트 1회로 원자 판정 | PostgreSQL 단독(07:00 몰림에서 행 잠금 경합) · Redis+PG 동기 쓰기(지연 · DB 부하) |
| 정본 · 영구 기록 | PostgreSQL 16 | MySQL(차이 작음, 부분 인덱스 · `ON CONFLICT` 선호) |
| 이벤트 로그 | Redis Stream (Lua 안에서 XADD = outbox) | Kafka(compose · 운영 무거움, 이 규모에 과함) |
| 실시간 전달 | **SSE**(`text/event-stream` · HTTP/2) + Redis sharded Pub/Sub(그룹 채널). 서버 → 클라이언트 단방향 push 만 필요하고 명령은 모두 REST 라 양방향 채널이 필요 없다. 재접속은 `Last-Event-ID` + 접속 직후 현황 스냅샷 | WebSocket(미션 중 클라이언트 → 서버 메시지가 없어 양방향이 과함 · 업그레이드 · 프록시 설정 부담) · 폴링(250만 × 주기 비용) |
| 인증 | 가입 · 로그인 → JWT(Access) · SSE 는 접속 시 토큰 검증(웹 `EventSource` 는 헤더를 못 실어 쿠키 또는 짧은 수명 구독 티켓을 쓴다) | 서버 세션(무상태 수평 확장에 불리) |
| 마감 | `worker` 의 Deadline 잡이 08:00:03 에 미완료 그룹을 같은 Lua 로 실패 확정 | DB 배치(정본과 핫 상태 이중 판정 위험) |

## 2. 구성도 (그림 1)

| 구성 요소 | 역할 | 상태 |
|---|---|---|
| Client (앱 · 웹) | REST 로 명령, SSE 로 현황 수신 | 로컬 스토리지 |
| `api` (:8080) | 가입 · 로그인 · 게임 정보 · 문제은행 · 풀이/패스/실패 신호 · 결과 조회 | 없음(무상태) |
| `sse` (:8081) | SSE 연결 유지 · 하트비트(주석 줄 15초), 접속 유저 그룹 채널 구독 · 전달, 접속 직후 현황 스냅샷 | 연결만(무상태) |
| `worker` | Persister(Stream → PG 멱등 upsert) · Deadline(08:00:03 확정) · 07:00 명단 확정 | 없음(소비 위치는 Redis consumer group) |
| Redis 7 | 그룹 핫 상태 · Lua 판정 · Stream · Pub/Sub · 출제 이력 캐시 | AOF everysec |
| PostgreSQL 16 | users · groups · questions · served_questions · daily_progress · group_results | WAL |

흐름(그림 1의 번호):
1. ① Client → `api`(:8080) 풀이 신호 (REST) — 로컬은 LB 없이 포트로 직접 붙는다
2. ② `api` → Redis `EVALSHA solve.lua` (한 번의 원자 실행)
3. ③ Lua 안: 순서 · 멱등 검사 → 도착 시각(`TIME`) · D1 마감 판정 → 진행도 반영 → 그룹 판정(`HSETNX result`) → `XADD events`
4. ④ 같은 Lua 안에서 `SPUBLISH group:{gid}` — 그 그룹을 구독한 `sse` 가 받는다
5. ⑤ `sse`(:8081) → 그룹원 SSE 스트림으로 현황 push (`event:` 이름 · `id:` = Stream 이벤트 id)
6. ⑥ `worker` Persister 가 Stream 을 `XREADGROUP` 으로 읽는다
7. ⑦ PostgreSQL 에 멱등 upsert 후 `XACK` (테이블 · DDL · ERD 는 `db.md`)

## 3. 정합성 조건별 보장

| 조건 | 보장 방식 |
|---|---|
| NFR-1 마감(D1) | 도착 시각은 Lua 안 Redis `TIME`(단일 시계). `도착 < 08:00:00` 이면 반영, `08:00:00 ≤ 도착 < 08:00:03` 이고 클릭 시각 `< 08:00:00` 이면 반영, 그 밖은 거부. 앱 서버 시계는 쓰지 않는다 |
| NFR-2 1회 기록 | 그룹 결과를 Lua 의 `HSETNX` 로 한 번만 쓰고 그때만 이벤트 발행. PG 는 `UNIQUE(group_id, game_date)` + `ON CONFLICT DO NOTHING` 으로 한 번 더 막는다 |
| NFR-3 유실 · 중복 없음 | 그룹 키가 같은 해시 슬롯(`{gid}`)이라 그룹 안 신호가 Lua 로 직렬화된다. 자연키(날짜 · 유저 · 문제 번호)로 멱등 — 이미 반영된 번호는 같은 응답 |
| NFR-4 랭킹 일치 | 랭킹 키(진행도 · 서버 도착 시각 · 패스 단계 · 지각 · 부정 실패)를 모두 Redis 그룹 Hash 에 둔다. 결과 확정 시 그 스냅샷으로 랭킹을 계산해 결과 이벤트에 싣고 PG 에 그대로 저장 — 결과 조회는 그 저장본을 읽는다 |
| NFR-5 재시작 내구성 | `api` · `sse` · `worker` 는 무상태라 재시작해도 잃는 것이 없다. Redis 는 AOF `everysec` — Redis 프로세스 자체가 죽으면 **최대 1초** 유실 가능(문서에 명시하는 허용 범위). Stream 은 PG 로 영속되므로 Redis 가 비면 PG 에서 그날 상태를 다시 올린다(rehydrate) |

## 4. 데이터 배치

| 데이터 | Redis 키 | PostgreSQL |
|---|---|---|
| 그룹 하루 상태 | `game:{date}:g:{gid}` Hash (멤버별 진행도 · 도착 시각 · 패스 · 지각 · 실패 · 그룹 결과) | `daily_progress` · `group_results` |
| 그날 그룹원 명단 | `game:{date}:g:{gid}:members` (07:00 확정) | `users.joined_at < 07:00` 로 재계산 가능 |
| 유저 출제 이력 | `served:{uid}` Set (캐시) | `served_questions` (정본, 평생 중복 금지) |
| 문제 | `api` 메모리 캐시 | `questions` (정답 · 정답 해시 별도 컬럼) |
| 결과 랭킹 스냅샷 | 결과 이벤트에 실음 | `group_result_members` |
| 이벤트 | `events:{shard}` Stream | — (Persister 가 반영) |

- 테이블 정의 · DDL · ERD 의 정본은 `db.md`(DB 설계)다.

## 5. 규모 · 부하 (산술, 실측 아님)

| 양 | 값 | 대응 |
|---|---|---|
| 동시 SSE 연결 | 최대 250만 | `sse` 1대당 5만 기준 약 50대(운영). 그룹 채널 구독 수 = 접속 그룹 수 |
| 풀이 신호 평균 | 약 2,083 건/초 | Lua 1회 ≈ 수십 µs. 피크 10배(2만 건/초)도 Redis Cluster 샤드 수 개로 충분 |
| 07:00 문제은행 요청 | 최대 250만 건/수 분 | 문제 본문은 메모리 캐시, 유저 이력만 Redis 조회 |
| 그룹 수 | 25만~125만 | 그룹 Hash 1개당 수백 B → 수백 MB |

## 6. 로컬 실행 (`docker compose up`)

| 서비스 | 이미지 | 비고 |
|---|---|---|
| `postgres` | postgres:16 | 이름 있는 볼륨 · 초기 스키마 · 문제 시드 |
| `redis` | redis:7 | `appendonly yes` · `appendfsync everysec` · 이름 있는 볼륨 |
| `api` | 앱 이미지 (profile=api) | `8080:8080` · healthcheck 후 기동 |
| `sse` | 앱 이미지 (profile=sse) | `8081:8081` · 클라이언트가 직접 연결 |
| `worker` | 앱 이미지 (profile=worker) | |
| (선택) `clock` | — | 테스트용 게임 시각 오프셋 설정(`GAME_CLOCK_OFFSET`) — 07:00 을 기다리지 않고 검증 |

## 7. 운영 환경 확장 (문서로만)

| 항목 | 운영 |
|---|---|
| Redis | Redis Cluster(그룹 해시태그로 샤딩) + replica, 장애 시 `WAIT 1` 로 유실 창 축소 |
| PostgreSQL | 관리형 + 읽기 replica(결과 조회) · `served_questions` 파티셔닝 |
| `sse` | 오토스케일 · 07:00 전 미리 확장(예약 스케일) · 연결 수 기반 |
| api · worker | HPA, worker 는 Stream 샤드 수만큼 |
| 진입 | L7 LB 추가 — 경로로 `api` · `sse` 분배, SSE 경로는 버퍼링 끔 · 긴 idle timeout (HTTP/2 — 브라우저 도메인당 연결 수 제한 회피) + SSE sticky 불필요(상태 없음) · 정적 문제 본문은 CDN 고려 |
| 관측 | 신호 처리 지연 · 거부 사유 · Stream lag · 연결 수 대시보드 |

## 8. 남은 결정

| 질문 | 영향 |
|---|---|
| 노출만 된 문제의 출제 이력 기록 (prd Q3) | `served:{uid}` 채우는 시점 |
| 문제은행 크기 · 고갈 (prd Q9) | 응답 크기 · 캐시 구조 |
| 성공한 유저의 실패 신호 (prd Q7) | Lua 판정 분기 |

## 9. DB — ERD · DDL

DB 설계 문서(`db.md`)와 같은 내용이다 — 둘이 어긋나면 `db.md` 가 정본이다.

- **CHECK 제약은 두지 않는다.** 값 범위 · 허용 값 검증은 모두 백엔드가 한다 — 규칙이 바뀔 때(정원 · 상태 값 · 문제 번호 범위) 마이그레이션 없이 확장하기 위해서다. 허용 값 · 의미는 DDL 의 `COMMENT ON` 에 남긴다(검증 규칙의 정본). PK · UNIQUE · FK · NOT NULL 은 정합성(NFR-2 · NFR-3)의 마지막 방어선이라 남긴다.

| 관계 | 카디널리티 | 키 |
|---|---|---|
| groups → users | 1 : 2~10 | `users.group_id` |
| users → served_questions | 1 : N (평생) | `served_questions.user_id` |
| questions → served_questions | 1 : N | `served_questions.question_id` |
| users → daily_progress | 1 : 하루 1 | `(game_date, user_id)` |
| daily_progress → solve_signals | 1 : 0~3 | `(game_date, user_id, question_no)` |
| groups → group_results | 1 : 하루 0~1 | `(game_date, group_id)` |
| group_results → group_result_members | 1 : 2~10 | `(game_date, group_id)` |

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
COMMENT ON COLUMN groups.name         IS '화면에 보이는 그룹명(가입 시 입력 그대로). 길이 · 허용 문자는 백엔드 검증(prd Q10).';
COMMENT ON COLUMN groups.name_key     IS '유일성 비교용 정규화 그룹명(R-G2). 정규화 규칙(대소문자 · 공백)은 백엔드가 정한다(prd Q10). UNIQUE 로 동시 가입 경합을 막는다.';
COMMENT ON COLUMN groups.member_count IS '현재 소속 인원. 허용 값 0~10 — 10 이면 11번째 가입을 「그룹이 꽉 찼다」로 거부(R-G3). 증가는 백엔드가 조건부 UPDATE(member_count < 10)로 원자 처리. 07:00 이후 가입자를 세는지는 prd Q11.';
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
COMMENT ON COLUMN users.id            IS '유저 PK. 현황판의 유저 ID 로도 쓴다.';
COMMENT ON COLUMN users.login_id      IS '로그인 아이디. 유일. 길이 · 허용 문자는 백엔드 검증.';
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
COMMENT ON COLUMN questions.answer_hash IS '정답 해시 — 띄어쓰기 전부 제거 → 소문자 → 앞뒤 공백 제거 후 해시(R-A2). 함수 · salt 는 prd Q5.';

CREATE TABLE served_questions (
    user_id      BIGINT       NOT NULL REFERENCES users(id),
    question_id  BIGINT       NOT NULL REFERENCES questions(id),
    game_date    DATE         NOT NULL,
    question_no  SMALLINT     NOT NULL,
    kind         VARCHAR(8)   NOT NULL,
    served_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, question_id)
);
COMMENT ON TABLE  served_questions             IS '유저에게 이미 낸 문제. PK(user_id, question_id)로 평생 중복 금지(R-B7 · R-S2 · R-P3). 문제은행을 줄 때 여기 있는 문제를 뺀다(R-B3).';
COMMENT ON COLUMN served_questions.user_id     IS '유저.';
COMMENT ON COLUMN served_questions.question_id IS '낸 문제(questions.id).';
COMMENT ON COLUMN served_questions.game_date   IS '낸 게임 날짜(KST).';
COMMENT ON COLUMN served_questions.question_no IS '그날 몇 번 문제 자리였는지. 허용 값 1 · 2 · 3.';
COMMENT ON COLUMN served_questions.kind        IS '어떻게 끝난 문제인지. 허용 값 SOLVED(맞힘 — 풀이 신호) · PASSED(패스로 넘김 — 패스 신호). 노출만 된 문제(SHOWN)를 넣을지는 prd Q3.';
COMMENT ON COLUMN served_questions.served_at   IS '서버가 그 신호를 받은 시각(Redis TIME).';

CREATE TABLE daily_progress (
    game_date          DATE         NOT NULL,
    user_id            BIGINT       NOT NULL REFERENCES users(id),
    group_id           BIGINT       NOT NULL REFERENCES groups(id),
    progress           SMALLINT     NOT NULL DEFAULT -1,
    status             VARCHAR(12)  NOT NULL DEFAULT 'IN_PROGRESS',
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
COMMENT ON COLUMN daily_progress.status             IS '유저 미션 상태. 허용 값 IN_PROGRESS(진행 중 · 시작 전 포함) · SUCCEEDED(3문제 완료) · CHEAT_FAILED(로컬 스토리지 빔으로 실패 처리, R-F2). SUCCEEDED · CHEAT_FAILED 는 「완료」이며 다시 바뀌지 않는다(성공 뒤 실패 신호는 prd Q7).';
COMMENT ON COLUMN daily_progress.started_at         IS '미션 시작 시각 = 07:00 이후 처음 문제은행을 받은 서버 시각(R-B1). NULL = 시작 전.';
COMMENT ON COLUMN daily_progress.is_late            IS '지각 여부(진행도 0 동률 정렬용, §6). started_at 이 07:00 + 허용 오차보다 늦으면 TRUE. 허용 오차는 prd Q6. NULL = 시작 전.';
COMMENT ON COLUMN daily_progress.last_solved_at     IS '마지막으로 반영된 풀이 신호의 서버 도착 시각(Redis TIME). 랭킹 2순위 키(R-S5). successTime 은 쓰지 않는다.';
COMMENT ON COLUMN daily_progress.pass_question_no   IS '패스를 쓴 문제 번호. 허용 값 NULL(안 씀) · 1 · 2 · 3. 하루 1회 — 이미 값이 있으면 2번째 패스는 거부(R-P1 · R-P5). 랭킹 3순위 키(높은 번호에서 쓴 쪽이 위).';
COMMENT ON COLUMN daily_progress.passed_question_id IS '패스로 넘긴 문제. pass_question_no 와 함께 채워진다.';
COMMENT ON COLUMN daily_progress.cheat_failed_at    IS '부정 실패 처리 시각. status = CHEAT_FAILED 일 때만 값이 있다. 부정 실패 유저는 랭킹 꼴등 동률(§6 0순위).';
COMMENT ON COLUMN daily_progress.updated_at         IS '이 행이 마지막으로 반영된 시각(Persister 기준).';

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
COMMENT ON TABLE  group_results              IS '그룹의 하루 결과. PK(game_date, group_id) + ON CONFLICT DO NOTHING 으로 정확히 한 번 기록(NFR-2). 그날 그룹원 1명인 그룹은 행이 없다(R-G6). game_date 범위 파티션.';
COMMENT ON COLUMN group_results.game_date    IS '게임 날짜(KST).';
COMMENT ON COLUMN group_results.group_id     IS '그룹.';
COMMENT ON COLUMN group_results.result       IS '결과. 허용 값 SUCCESS(그날 그룹원 전원 미션 성공) · FAIL_IMMEDIATE(전원 완료인데 부정 실패가 한 명 이상, R-F6) · FAIL_DEADLINE(08:00:03 에 아직 진행 중, R-D3).';
COMMENT ON COLUMN group_results.completed_at IS '결과가 확정된 서버 시각(Redis TIME). SUCCESS 면 그룹 완료 시각.';
COMMENT ON COLUMN group_results.member_count IS '그날의 그룹원 수(07:00 이전 가입자). 허용 값 2~10.';
COMMENT ON COLUMN group_results.recorded_at  IS 'PostgreSQL 에 기록된 시각(Persister).';

CREATE TABLE group_result_members (
    game_date     DATE         NOT NULL,
    group_id      BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    rank          SMALLINT     NOT NULL,
    progress      SMALLINT     NOT NULL,
    status        VARCHAR(12)  NOT NULL,
    PRIMARY KEY (game_date, user_id),
    FOREIGN KEY (game_date, group_id) REFERENCES group_results (game_date, group_id)
) PARTITION BY RANGE (game_date);
COMMENT ON TABLE  group_result_members          IS '결과 확정 시점의 그룹 내 랭킹 스냅샷. 결과 조회는 이것만 읽어 최종 상태와 일치시킨다(NFR-4). group_results 와 같은 트랜잭션으로 한 번만 삽입. game_date 범위 파티션.';
COMMENT ON COLUMN group_result_members.game_date IS '게임 날짜(KST).';
COMMENT ON COLUMN group_result_members.group_id  IS '그룹.';
COMMENT ON COLUMN group_result_members.user_id   IS '그날의 그룹원.';
COMMENT ON COLUMN group_result_members.rank      IS '그룹 내 순위. 허용 값 1~10, 동률은 같은 값(§6 규칙으로 계산).';
COMMENT ON COLUMN group_result_members.progress  IS '확정 시점 진행도. 허용 값 -1 · 0 · 1 · 2 · 3(화면 표기는 -1 → 0).';
COMMENT ON COLUMN group_result_members.status    IS '결과 화면에 보이는 상태. 허용 값 SUCCEEDED(성공) · FAILED(실패 — 부정 실패 포함, R-F4) · IN_PROGRESS(마감 때 미완료).';

-- 하루 파티션 (worker 가 전날 미리 만든다)
CREATE TABLE daily_progress_20261111       PARTITION OF daily_progress       FOR VALUES FROM ('2026-11-11') TO ('2026-11-12');
CREATE TABLE solve_signals_20261111        PARTITION OF solve_signals        FOR VALUES FROM ('2026-11-11') TO ('2026-11-12');
CREATE TABLE group_results_20261111        PARTITION OF group_results        FOR VALUES FROM ('2026-11-11') TO ('2026-11-12');
CREATE TABLE group_result_members_20261111 PARTITION OF group_result_members FOR VALUES FROM ('2026-11-11') TO ('2026-11-12');
```
