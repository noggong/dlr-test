#!/usr/bin/env python3
"""로컬 회원 · 그룹 시드 SQL 을 만든다 — 정본은 docs/lld/platform.md §2.3.

회원 500명(seed0001 ~ seed0500 · 비밀번호 모두 seed-pass-1)을 고정 시드로 섞어 2~10명씩 그룹으로 나눈다.
api 프로필 local-seed 일 때만 data.sql 뒤에 적용된다(SPEC platform P18).
    python3 tools/local_seed.py           # backend/infra/rdb/src/main/resources/local-seed.sql 을 다시 쓴다
    python3 tools/local_seed.py --check   # 파일이 이 스크립트의 결과와 같은지만 본다(다르면 종료 코드 1)
"""
import random
import sys
from pathlib import Path

import bcrypt

OUT = Path(__file__).resolve().parent.parent / "backend" / "infra" / "rdb" / "src" / "main" / "resources" / "local-seed.sql"

USERS = 500
MIN_SIZE, MAX_SIZE = 2, 10  # R-G3 정원 10 · 혼자인 그룹은 두지 않는다
RANDOM_SEED = 20261111
# 테스트용 값 — 비밀이 아니다(README 「사용자 테스트」 · SPEC platform §7)
PASSWORD = b"seed-pass-1"
# 가입과 같은 BCrypt $2a$ cost 10(auth-signup LLD §2.1). salt(22자 — 마지막 글자는 아래 4비트가 0 인 것만)를 고정해 다시 돌려도 같은 해시가 나온다
SALT = b"$2a$10$" + b"LocalSeedSaltOnlyForDe"
JOINED_AT = "2025-01-01 00:00:00+09"  # 어느 게임일에서도 그날 06:50 전(prd D24)
LOCK_KEY = 1296651091  # 스키마 잠금 — SchemaLock.KEY(LLD platform §4.3)


def login_id(n: int) -> str:
    return f"seed{n:04d}"


def group_name(no: int) -> str:
    return f"시드 그룹 {no:03d}"


def partition(rng: random.Random) -> list[list[int]]:
    """1..USERS 를 섞어 앞에서부터 2~10명씩 끊는다. 마지막 그룹도 2명 이상이 되게 남은 수를 본다."""
    ids = list(range(1, USERS + 1))
    rng.shuffle(ids)
    groups, i = [], 0
    while i < len(ids):
        left = len(ids) - i
        size = left if left <= MAX_SIZE else rng.randint(MIN_SIZE, min(MAX_SIZE, left - MIN_SIZE))
        groups.append(sorted(ids[i:i + size]))
        i += size
    return groups


def render() -> str:
    groups = partition(random.Random(RANDOM_SEED))
    assert sum(map(len, groups)) == USERS and all(MIN_SIZE <= len(g) <= MAX_SIZE for g in groups)
    pw_hash = bcrypt.hashpw(PASSWORD, SALT).decode()
    assert bcrypt.checkpw(PASSWORD, pw_hash.encode()) and pw_hash.startswith("$2a$10$")
    values = ",\n".join(
        f"        ({no}, '{group_name(no)}', ARRAY[{', '.join(map(str, g))}])" for no, g in enumerate(groups, 1)
    )
    return f"""-- ============================================================
-- 로컬 회원 · 그룹 시드 — 정본은 docs/lld/platform.md §2.3. 이 파일은 tools/local_seed.py 가 만든다(손으로 고치지 않는다).
-- api 프로필 local-seed 일 때만 data.sql 뒤에 적용된다(application-local-seed.yml · SPEC platform P18) — 운영 · 테스트 DB 에는 없다.
-- 회원 {USERS}명 {login_id(1)} ~ {login_id(USERS)} · 비밀번호 모두 {PASSWORD.decode()}(테스트용 값) · 그룹 {len(groups)}개(그룹마다 {MIN_SIZE}~{MAX_SIZE}명).
-- 그룹 · 회원을 한 문장으로 넣는다. 시드 아이디 · 그룹명이 하나라도 이미 있으면 0행(전부 또는 없음) — 다시 기동해도 같고,
-- 실제 사용자의 계정 · 그룹 인원을 바꾸지 않는다. 동시 기동은 스키마 잠금(LLD §4.3)이 차례를 세운다.
-- ============================================================

SELECT pg_advisory_lock({LOCK_KEY});

WITH seed_group (no, name, members) AS (
    VALUES
{values}
),
gate AS (
    SELECT 1
    WHERE NOT EXISTS (SELECT 1 FROM users WHERE login_id IN (SELECT 'seed' || lpad(n::text, 4, '0') FROM generate_series(1, {USERS}) AS n))
      AND NOT EXISTS (SELECT 1 FROM groups g JOIN seed_group s ON g.name_key = s.name)
),
new_group AS (
    INSERT INTO groups (name, name_key, member_count, created_at)
    SELECT s.name, s.name, cardinality(s.members), TIMESTAMPTZ '{JOINED_AT}'
    FROM seed_group s CROSS JOIN gate
    ORDER BY s.no
    RETURNING id, name_key
)
INSERT INTO users (login_id, password_hash, group_id, joined_at)
SELECT 'seed' || lpad(m::text, 4, '0'), '{pw_hash}', g.id, TIMESTAMPTZ '{JOINED_AT}'
FROM seed_group s
JOIN new_group g ON g.name_key = s.name
CROSS JOIN LATERAL unnest(s.members) AS m
ORDER BY m;

SELECT pg_advisory_unlock({LOCK_KEY});
"""


def main() -> int:
    text = render()
    if "--check" in sys.argv[1:]:
        if OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} 가 생성 결과와 다릅니다 — python3 tools/local_seed.py 로 다시 쓰세요", file=sys.stderr)
            return 1
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
