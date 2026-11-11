-- 진입 신호(LLD game-session §4.3) — 문제 화면에 들어갈 때마다 부른다. 그 번호의 「지금 문제」를 고정한다(SPEC GS9 ~ GS11).
-- KEYS[1] 그룹 Hash g:{b}:gid:D · KEYS[2] 명단 Hash g:{b}:gid:D:members · KEYS[3] 이벤트 Stream ev:{b}
-- ARGV[1] uid · ARGV[2] questionNo(1~3) · ARGV[3] questionId(pk) · ARGV[4] 내부 ID(qok 0 이면 '') · ARGV[5] qok(1 = 있는 PK 이고 난이도 = 번호)
--      · ARGV[6] startUs(D 07:00) · ARGV[7] deadlineUs(D 08:00) · ARGV[8] offsetUs · ARGV[9] D · ARGV[10] gid
-- 반환(칸은 모두 문자열) — 판정 순서는 SPEC GS9:
--   { 'ERR', code }               GAME_NOT_OPEN · SUBMISSION_CLOSED · NOT_PARTICIPANT · SOLO_GROUP · MISSION_NOT_STARTED
--                                 · MISSION_ALREADY_FINISHED · QUESTION_MISMATCH · QUESTION_OUT_OF_ORDER
--   { 'NO_ROSTER' }               명단이 아직 없다 — 앱이 만들고 한 번 다시 부른다
--   { 'OK', p, st, created }      created '1' = 이번 호출이 고정했다(question.shown 1건), '0' = 이미 같은 문제(멱등 — 아무것도 바꾸지 않는다)
-- 진입 신호는 현황판에 보이는 값을 바꾸지 않으므로 version 을 올리지 않고 publish 하지 않는다. 출제 이력 중복은 보지 않는다(D15 — persister).
local group, members, ev = KEYS[1], KEYS[2], KEYS[3]
local uid = ARGV[1]
if not string.match(ARGV[2], '^[123]$') then error('questionNo must be 1~3: ' .. tostring(ARGV[2])) end
local questionNo, pk, qid = tonumber(ARGV[2]), ARGV[3], ARGV[4]
local qok = ARGV[5]
if qok ~= '0' and qok ~= '1' then error('qok must be 0 or 1: ' .. tostring(qok)) end
if qok == '1' and not string.match(qid, '^%d+$') then error('qid must be an integer when qok = 1: ' .. tostring(qid)) end
local startUs = common.int(ARGV[6], 'startUs')
local deadlineUs = common.int(ARGV[7], 'deadlineUs')
local t = common.now(ARGV[8])
local date, gid = ARGV[9], ARGV[10]

-- 1. 시간 창(GS2) — startUs ≤ t < deadlineUs 만 통과(유예 없음)
if t < startUs then return { 'ERR', 'GAME_NOT_OPEN' } end
if t >= deadlineUs then return { 'ERR', 'SUBMISSION_CLOSED' } end

-- 2. 명단 · 그날 그룹원(GS3) · 1명 그룹(GS4)
local size = redis.call('HGET', members, '#size')
if not size then return { 'NO_ROSTER' } end
local lid = redis.call('HGET', members, uid)
if not lid then return { 'ERR', 'NOT_PARTICIPANT' } end
if size == '1' then return { 'ERR', 'SOLO_GROUP' } end

-- 3. 내 상태. FAILED 는 08:00 마감에서만 생겨 1 에서 걸린다 — 그래도 생기면 끝난 미션으로 본다(fail.lua 와 같은 태도)
local m = redis.call('HMGET', group, uid .. ':p', uid .. ':st', uid .. ':cur', 'version', uid .. ':pn', uid .. ':pq')
local ps, st, cur, version, pn, pq = m[1], m[2], m[3], m[4], m[5], m[6]
if not ps or not st then error('shown: group hash has no member ' .. uid .. ': ' .. group) end
if not string.match(ps, '^%-?%d$') then error('shown: bad ' .. uid .. ':p: ' .. ps) end
local p = tonumber(ps)
if p < -1 or p > 3 then error('shown: bad ' .. uid .. ':p: ' .. ps) end
if st == 'NOT_STARTED' then return { 'ERR', 'MISSION_NOT_STARTED' } end
if st == 'SUCCEEDED' or st == 'CHEAT_FAILED' or st == 'FAILED' then return { 'ERR', 'MISSION_ALREADY_FINISHED' } end
if st ~= 'IN_PROGRESS' then error('shown: bad ' .. uid .. ':st: ' .. st) end

-- 4. 없는 PK · 난이도 ≠ 번호(앱이 문제 캐시로 본 결과)
if qok == '0' then return { 'ERR', 'QUESTION_MISMATCH' } end

-- 5. 번호 = 진행도 + 1 만(이미 푼 번호의 늦은 재시도 포함 — GS10)
if questionNo ~= p + 1 then return { 'ERR', 'QUESTION_OUT_OF_ORDER' } end

-- 6. 지금 문제(GS10 · GS11) — 번호는 언제나 진행도 + 1 이라 칸 하나로 본다
if cur == pk then return { 'OK', ps, st, '0' } end
if cur then return { 'ERR', 'QUESTION_MISMATCH' } end -- 바꾸는 길은 패스뿐(solve-signal)
-- 패스로 넘긴 문제는 다시 고정하지 않는다 — 패스 앞 진입 신호의 늦은 재시도가 되살리면 교체 문제가 막힌다(GS11)
if pn == ARGV[2] and pq == pk then return { 'ERR', 'QUESTION_MISMATCH' } end

local ts = common.fmt(t)
redis.call('HSET', group, uid .. ':cur', pk)
-- 이벤트(필드 정본 HLD platform §4.2) — version 은 올리지 않은 지금 값. persister 가 served_questions 에 옮긴다
common.xadd(ev, {
  'type', 'question.shown', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid,
  'version', common.fmt(tonumber(version or '0')), 'at', ts,
  'questionNo', ARGV[2], 'questionId', pk, 'qid', qid, 'shownAt', ts,
})
return { 'OK', ps, st, '1' }
