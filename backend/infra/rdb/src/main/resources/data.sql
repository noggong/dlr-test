-- ============================================================
-- 문제 시드 — 정본은 docs/lld/platform.md §2.2. schema.sql 바로 뒤에 같은 기동에서 적용된다(spring.sql.init 기본 data.sql).
-- 실제 시사 문제가 아니라 생성 규칙으로 만든 문제다(로컬 실행 · 테스트용). 난이도마다 120개.
-- pk_hash 가 (난이도, 번호)로 정해지므로 ON CONFLICT DO NOTHING 으로 몇 번 적용해도 같다.
-- answer_hash = SHA-256(UTF-8("<pk_hash>:<정규화한 답>")) 소문자 hex(prd D7) · 정규화 = 공백 전부 제거 → 소문자(R-A2).
-- ============================================================

INSERT INTO questions (pk_hash, difficulty, category, body, answer, answer_hash)
SELECT q.pk_hash,
       q.difficulty,
       q.category,
       format('[시드 %s-%s · %s] 다음 말을 그대로 입력하세요: %s', q.difficulty, q.no, q.category, q.answer),
       q.answer,
       encode(sha256(convert_to(q.pk_hash || ':' || lower(regexp_replace(q.answer, '[[:space:]]', '', 'g')), 'UTF8')), 'hex')
FROM (
    SELECT d AS difficulty,
           lpad(n::text, 3, '0') AS no,
           encode(sha256(convert_to('seed:question:' || d || ':' || n, 'UTF8')), 'hex') AS pk_hash,
           (ARRAY['POLITICS', 'CURRENT', 'SOCIETY', 'GENERAL'])[(n - 1) % 4 + 1] AS category,
           'Mission ' || d || '-' || lpad(n::text, 3, '0') || ' 정답' AS answer
    FROM generate_series(1, 3) AS d, generate_series(1, 120) AS n
) AS q
ORDER BY q.difficulty, q.no
ON CONFLICT (pk_hash) DO NOTHING;
