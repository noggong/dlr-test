-- ============================================================
-- 문제 시드 — 정본은 docs/lld/platform.md §2.2. schema.sql 바로 뒤에 같은 기동에서 적용된다(spring.sql.init 기본 data.sql).
-- 실제 시사 문제가 아니라 생성 규칙으로 만든 문제다(로컬 실행 · 테스트용). 난이도마다 120개.
-- pk_hash 가 (난이도, 번호)로 정해지므로 몇 번 적용해도 같다. 이미 있는 행은 NOT EXISTS 로 먼저 걸러 id 시퀀스를 쓰지 않고,
-- 동시 기동 경합은 ON CONFLICT DO NOTHING 이 막는다.
-- answer_hash = SHA-256(UTF-8("<pk_hash>:<정규화한 답>")) 소문자 hex(prd D7) · 정규화(R-A2 · D22) = 공백 문자 33개 전부 제거 → NFC → A-Z 만 소문자.
-- 공백은 [[:space:]](로캘마다 범위가 다르다) 대신 하나씩 적고, 소문자는 lower(콜레이션을 따른다) 대신 translate 로 A-Z 만 바꾼다.
-- 정규화 식은 아래 「-- R-A2 정규화」 한 줄에만 둔다 — SchemaSeedTest(ORACLE O35)가 그 줄을 읽어 공유 벡터와 견준다.
-- ============================================================

INSERT INTO questions (pk_hash, difficulty, category, body, answer, answer_hash)
SELECT q.pk_hash,
       q.difficulty,
       q.category,
       format('[시드 %s-%s · %s] 다음 말을 그대로 입력하세요: %s', q.difficulty, q.no, q.category, q.answer),
       q.answer,
       encode(sha256(convert_to(q.pk_hash || ':' ||
           translate(normalize(regexp_replace(q.answer, '[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\u200b\u200c\u200d\u2060\ufeff\u180e\u3164\uffa0]', '', 'g'), NFC), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') -- R-A2 정규화
       , 'UTF8')), 'hex')
FROM (
    SELECT d AS difficulty,
           lpad(n::text, 3, '0') AS no,
           encode(sha256(convert_to('seed:question:' || d || ':' || n, 'UTF8')), 'hex') AS pk_hash,
           (ARRAY['POLITICS', 'CURRENT', 'SOCIETY', 'GENERAL'])[(n - 1) % 4 + 1] AS category,
           'Mission ' || d || '-' || lpad(n::text, 3, '0') || ' 정답' AS answer
    FROM generate_series(1, 3) AS d, generate_series(1, 120) AS n
) AS q
WHERE NOT EXISTS (SELECT 1 FROM questions x WHERE x.pk_hash = q.pk_hash)
ORDER BY q.difficulty, q.no
ON CONFLICT (pk_hash) DO NOTHING;
