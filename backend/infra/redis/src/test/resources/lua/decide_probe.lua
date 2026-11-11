-- 테스트 래퍼 — decide.run 을 시각을 직접 넣어 부른다(ORACLE solve-signal · DecideScriptTest).
-- KEYS[1] = 그룹 Hash · KEYS[2] = 명단 Hash · ARGV[1] = t(µs) · ARGV[2] = deadlineAt(µs). 반환: 이번에 쓴 결과 또는 ''.
local uids = decide.uids(KEYS[2])
return decide.run(KEYS[1], uids, common.int(ARGV[1], 't'), common.int(ARGV[2], 'deadlineAt')) or ''
