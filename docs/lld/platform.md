# LLD — platform (DB 설계)

> 판 v1 · 2026-11-10 · 짝 문서: [HLD](../hld/platform.md) · [SPEC](../spec/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) v2 · 원본 산출물: `docs/outputs/feature-infra-arch/db/`

**세 줄 요약**
- 바뀌는 것: PostgreSQL 스키마(테이블 8개 · 하루 파티션)와 Persister 의 멱등 쓰기 규칙.
- 데이터 모델: 마스터(`groups` · `users` · `questions` · `served_questions`) + `game_date` 파티션(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`). CHECK 없이 백엔드가 검증하고 허용 값은 `COMMENT ON` 에 둔다.
- 구현 순서: 스키마 → 파티션 생성 잡 → Persister 멱등 upsert → 재적재(rehydrate).

## 1. 모듈 · 파일
| 경로 | 새로/수정 | 역할 |
|---|---|---|
| 스키마 DDL (경로는 백엔드 워크트리가 정한다) | 새로 | 아래 §2 DDL 그대로 |
| `docker-compose.yml` | 새로 | `postgres` · `redis` · `api` · `sse` · `worker` ([HLD](../hld/platform.md) §8) |

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
| `served_questions` | 유저에게 낸 문제(맞힘 · 패스) — 평생 중복 금지(R-B7 · R-S2 · R-P3) | worker(Persister) | 하루 최대 1,000만 행 누적 |
| `daily_progress` | 유저 하루 상태 · 랭킹 키 | worker(Persister) | 하루 250만 행 |
| `solve_signals` | 반영된 풀이 신호 원장(감사 · 재적재) | worker(Persister) | 하루 최대 750만 행 |
| `group_results` | 그룹 하루 결과 — 정확히 한 번(NFR-2) | worker(Persister) | 하루 최대 125만 행 |
| `group_result_members` | 결과 확정 시점의 그룹 내 랭킹 스냅샷(NFR-4) | worker → api(결과 조회) | 하루 250만 행 |

- **CHECK 제약은 두지 않는다.** 값 범위 · 허용 값 검증은 모두 백엔드가 한다 — 규칙이 바뀔 때(정원 · 상태 값 · 문제 번호 범위) 마이그레이션 없이 확장하기 위해서다. 허용 값 · 의미는 DDL 의 `COMMENT ON` 에 남긴다(검증 규칙의 정본). PK · UNIQUE · FK · NOT NULL 은 정합성(NFR-2 · NFR-3)의 마지막 방어선이라 남긴다.
- 하루 단위 테이블(`daily_progress` · `solve_signals` · `group_results` · `group_result_members`)은 `game_date` 범위 파티션 — 오래된 날은 파티션째 보관 · 삭제.
- 진행 중 판정은 Redis 가 하고, PostgreSQL 은 Stream 을 받아 **멱등 upsert** 로 따라간다(`infra.md` §3). 모든 쓰기에 자연키 PK 와 `ON CONFLICT` 가 있어 같은 이벤트를 두 번 받아도 결과가 같다.
- 열린 질문에 걸린 곳: 노출만 된 문제 기록(prd Q3)은 `served_questions.kind` 에 `SHOWN` 을 둘지로 갈린다 · 그룹명 비교 규칙(Q10)은 `groups.name_key` 정규화 방식 · 정원 기준(Q11)은 `groups.member_count` 를 무엇으로 세는지.

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

## 3. 처리 절차
| 이벤트 | SQL |
|---|---|
| 미션 시작 | `INSERT INTO daily_progress … ON CONFLICT (game_date, user_id) DO NOTHING` |
| 풀이 반영 | `INSERT INTO solve_signals … ON CONFLICT DO NOTHING` → `UPDATE daily_progress SET progress = GREATEST(progress, :no), …` |
| 패스 | `INSERT INTO served_questions (kind='PASSED') … ON CONFLICT DO NOTHING` · `UPDATE daily_progress SET pass_question_no = :no WHERE pass_question_no IS NULL` |
| 부정 실패 | `UPDATE daily_progress SET status='CHEAT_FAILED', cheat_failed_at = :t WHERE status='IN_PROGRESS'` |
| 그룹 결과 | `INSERT INTO group_results … ON CONFLICT DO NOTHING` → 삽입됐을 때만 `group_result_members` 일괄 삽입(같은 트랜잭션) |

## 4. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| 같은 이벤트를 Persister 가 두 번 받음 | 자연키 PK + `ON CONFLICT DO NOTHING` · `GREATEST` | 결과 같음 |
| 그룹 결과 이벤트 중복 | `group_results` 삽입이 됐을 때만 `group_result_members` 삽입 | 결과 같음 |
| 다음 날 파티션 없음 | `worker` 가 전날 미리 생성 | — |
| Redis 가 비어 재시작 | 그날 `daily_progress` · `solve_signals` 로 그룹 Hash 재적재 | — |

## 5. 구현 순서
1. 스키마 DDL · 문제 시드
2. 하루 파티션 생성 잡
3. Persister(Stream → 멱등 upsert · `XACK`)
4. 재적재(rehydrate)
