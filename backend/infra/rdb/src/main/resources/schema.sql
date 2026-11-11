-- ============================================================
-- PostgreSQL 스키마 — 정본은 docs/lld/platform.md §2.1 DDL. 앱(api)이 기동할 때 적용한다(spring.sql.init.mode=always).
-- 다시 적용해도 같도록 IF NOT EXISTS 꼴로 쓴다. 문제 시드는 같은 기동에서 뒤따르는 data.sql(LLD §2.2).
-- 값 범위 · 허용 값은 CHECK 로 두지 않고 백엔드가 검증한다(확장성).
-- 각 컬럼의 허용 값 · 의미는 COMMENT 에 적는다 — 이 주석이 검증 규칙의 정본이다.
-- ============================================================

CREATE TABLE IF NOT EXISTS groups (
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

CREATE TABLE IF NOT EXISTS users (
    id             BIGSERIAL    PRIMARY KEY,
    login_id       VARCHAR(32)  NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,
    group_id       BIGINT       NOT NULL REFERENCES groups(id),
    joined_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS users_group_joined_idx ON users (group_id, joined_at);
COMMENT ON TABLE  users               IS '유저 · 자격 증명 · 소속 그룹(FR-2 · FR-8).';
COMMENT ON COLUMN users.id            IS '유저 PK(내부용). 클라이언트에 보이는 유저 ID 는 login_id 다.';
COMMENT ON COLUMN users.login_id      IS '로그인 아이디. 유일. 현황판 · 결과의 유저 ID(API userId)로 보인다(prd D5). 길이 · 허용 문자는 백엔드 검증.';
COMMENT ON COLUMN users.password_hash IS '비밀번호 해시(bcrypt 또는 argon2). 평문은 저장하지 않는다.';
COMMENT ON COLUMN users.group_id      IS '소속 그룹. 가입 이후 바뀌지 않는다.';
COMMENT ON COLUMN users.joined_at     IS '가입 시각. 그 날짜 06:50 KST 전이면 그날 그룹원(R-G4 · R-G5 · prd D24), 06:50 부터면 다음 날부터.';

CREATE TABLE IF NOT EXISTS questions (
    id           BIGSERIAL    PRIMARY KEY,
    pk_hash      CHAR(64)     NOT NULL UNIQUE,
    difficulty   SMALLINT     NOT NULL,
    category     VARCHAR(10)  NOT NULL,
    body         TEXT         NOT NULL,
    answer       TEXT         NOT NULL,
    answer_hash  CHAR(64)     NOT NULL
);
CREATE INDEX IF NOT EXISTS questions_difficulty_idx ON questions (difficulty);
COMMENT ON TABLE  questions             IS '문제은행. 정답과 정답 해시를 따로 저장하고 클라이언트에는 해시만 내려준다(R-A3).';
COMMENT ON COLUMN questions.id          IS '내부 PK(다른 테이블 FK 용). 클라이언트에 노출하지 않는다.';
COMMENT ON COLUMN questions.pk_hash     IS '클라이언트에 내려주는 문제 고유 PK(hash · 64자 hex). 풀이 · 패스 신호의 문제 PK 가 이 값이다(R-B2).';
COMMENT ON COLUMN questions.difficulty  IS '난이도. 허용 값 1 · 2 · 3 — 문제 번호와 같다(1번=1, 2번=2, 3번=3, R-B5).';
COMMENT ON COLUMN questions.category    IS '분야. 허용 값 POLITICS(정치) · CURRENT(시사) · SOCIETY(사회) · GENERAL(상식).';
COMMENT ON COLUMN questions.body        IS '문제 본문(주관식).';
COMMENT ON COLUMN questions.answer      IS '실제 정답. 내려주지 않는다(R-A3).';
COMMENT ON COLUMN questions.answer_hash IS '정답 해시 — 정규화(prd R-A2 · D22: 공백 문자 33개 전부 제거 → NFC → A-Z 만 소문자) 뒤 해시. 값 = SHA-256(UTF-8("<pk_hash>:<정규화한 답>")) 소문자 hex(prd D7).';

CREATE TABLE IF NOT EXISTS served_questions (
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

CREATE TABLE IF NOT EXISTS daily_progress (
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
CREATE INDEX IF NOT EXISTS daily_progress_group_idx ON daily_progress (game_date, group_id);
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

CREATE TABLE IF NOT EXISTS solve_signals (
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

CREATE TABLE IF NOT EXISTS group_results (
    game_date     DATE         NOT NULL,
    group_id      BIGINT       NOT NULL REFERENCES groups(id),
    result        VARCHAR(16)  NOT NULL,
    completed_at  TIMESTAMPTZ  NOT NULL,
    member_count  SMALLINT     NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (game_date, group_id)
) PARTITION BY RANGE (game_date);
CREATE INDEX IF NOT EXISTS group_results_group_idx ON group_results (group_id, game_date DESC);
COMMENT ON TABLE  group_results              IS '그룹의 하루 결과. PK(game_date, group_id) + ON CONFLICT DO NOTHING 으로 정확히 한 번 기록(NFR-2). 그날 그룹원 1명인 그룹은 행이 없다(R-G6). game_date 범위 파티션.';
COMMENT ON COLUMN group_results.game_date    IS '게임 날짜(KST).';
COMMENT ON COLUMN group_results.group_id     IS '그룹.';
COMMENT ON COLUMN group_results.result       IS '결과. 허용 값 SUCCEEDED(그날 그룹원 전원 미션 성공) · FAILED_IMMEDIATE(전원 완료인데 부정 실패가 한 명 이상, R-F6) · FAILED_DEADLINE(마감 확정 때 아직 진행 중 — 08:00, 3번 진행 중 그룹원이 있으면 08:00:03, R-D3 · prd D17). API GroupStatus 로는 SUCCEEDED → SUCCEEDED, FAILED_* → FAILED.';
COMMENT ON COLUMN group_results.completed_at IS '결과가 확정된 서버 시각(Redis TIME). SUCCEEDED 면 그룹 완료 시각.';
COMMENT ON COLUMN group_results.member_count IS '그날의 그룹원 수(그 날짜 06:50 전 가입자 — prd D24). 허용 값 2~10.';
COMMENT ON COLUMN group_results.recorded_at  IS 'PostgreSQL 에 기록된 시각(`worker: persister`).';

CREATE TABLE IF NOT EXISTS group_result_members (
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
CREATE INDEX IF NOT EXISTS group_result_members_group_idx ON group_result_members (game_date, group_id);
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

-- persister dead-letter(prd D20 · LLD platform §3.1) — 하루 단위가 아니라 파티션 없음.
CREATE TABLE IF NOT EXISTS persister_dead_letters (
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
