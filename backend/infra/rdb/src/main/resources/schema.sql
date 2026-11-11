-- ============================================================
-- PostgreSQL 스키마 — 정본은 docs/lld/platform.md §2.1 DDL. 앱(api)이 기동할 때 적용한다(spring.sql.init.mode=always).
-- 다시 적용해도 같도록 IF NOT EXISTS 꼴로 쓴다. 다른 테이블 · 인덱스는 platform 일감이 이 파일에 더한다.
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
COMMENT ON COLUMN groups.member_count IS '현재 소속 인원. 허용 값 0~10 — 10 이면 11번째 가입을 「그룹이 꽉 찼다」로 거부(R-G3). 증가는 백엔드가 조건부 UPDATE(member_count < 10)로 원자 처리. 07:00 이후 가입자도 센다(prd D12).';
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
COMMENT ON COLUMN users.joined_at     IS '가입 시각. 그 날짜 07:00 KST 이전이면 그날 그룹원(R-G4 · R-G5), 이후면 다음 날부터.';
