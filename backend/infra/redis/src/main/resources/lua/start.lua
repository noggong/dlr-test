-- 미션 시작(LLD game-session §4.2) — 문제은행 요청이 부른다. 처음 한 번만 진행도 -1 → 0 · 시작 시각 · 지각을 고정한다(SPEC GS5).
-- KEYS[1] 그룹 Hash g:{b}:gid:D · KEYS[2] 명단 Hash g:{b}:gid:D:members · KEYS[3] 이벤트 Stream ev:{b}
-- ARGV[1] uid · ARGV[2] startUs(D 07:00) · ARGV[3] lateUs(D 07:00:03) · ARGV[4] deadlineUs(D 08:00) · ARGV[5] offsetUs
--      · ARGV[6] 채널 ch:{b}:gid · ARGV[7] D · ARGV[8] gid
-- 반환(칸은 모두 문자열) — 판정 순서는 SPEC GS14:
--   { 'ERR', code }                 GAME_NOT_OPEN · SUBMISSION_CLOSED · NOT_PARTICIPANT · SOLO_GROUP
--   { 'NO_ROSTER' }                 명단이 아직 없다 — 앱이 만들고 한 번 다시 부른다
--   { 'OK', sa, created, p, st }    created '1' = 이번 호출이 시작시켰다, '0' = 이미 시작함(아무것도 바꾸지 않는다)
local group, members, ev = KEYS[1], KEYS[2], KEYS[3]
local uid = ARGV[1]
local startUs = common.int(ARGV[2], 'startUs')
local lateUs = common.int(ARGV[3], 'lateUs')
local deadlineUs = common.int(ARGV[4], 'deadlineUs')
local t = common.now(ARGV[5])
local channel, date, gid = ARGV[6], ARGV[7], ARGV[8]

-- 1. 시간 창(GS2) — startUs ≤ t < deadlineUs 만 통과
if t < startUs then return { 'ERR', 'GAME_NOT_OPEN' } end
if t >= deadlineUs then return { 'ERR', 'SUBMISSION_CLOSED' } end

-- 2. 명단
local size = redis.call('HGET', members, '#size')
if not size then return { 'NO_ROSTER' } end

-- 3. 그날 그룹원(GS3)
local lid = redis.call('HGET', members, uid)
if not lid then return { 'ERR', 'NOT_PARTICIPANT' } end

-- 4. 1명 그룹(GS4) — 그룹 Hash 가 없다
if size == '1' then return { 'ERR', 'SOLO_GROUP' } end

-- 5. 이미 시작함(재접속 · 재시도 · 동시 요청의 두 번째) — 바꾸지 않는다. 진행도는 줄지 않는다(ORACLE I6)
local cur = redis.call('HMGET', group, uid .. ':p', uid .. ':sa', uid .. ':st')
if not cur[1] then error('start: group hash has no member ' .. uid .. ': ' .. group) end
if cur[1] ~= '-1' then
  if not cur[2] then error('start: started member has no sa ' .. uid .. ': ' .. group) end
  return { 'OK', cur[2], '0', cur[1], cur[3] or '' }
end

-- 6. 반영 — 정확히 07:00:03.000000 은 지각이 아니다(D8: t > lateUs 만)
local ts = common.fmt(t)
local late = (t > lateUs) and '1' or '0'
redis.call('HSET', group, uid .. ':p', '0', uid .. ':st', 'IN_PROGRESS', uid .. ':sa', ts, uid .. ':late', late)
local v = common.fmt(redis.call('HINCRBY', group, 'version', 1))

-- 7. 이벤트(필드 정본 HLD platform §4.2) — persister 가 daily_progress 에 옮긴다
local e = common.xadd(ev, {
  'type', 'mission.started', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid,
  'version', v, 'at', ts, 'startedAt', ts, 'late', late,
})

-- 8. 그룹 채널(realtime-board LLD §2.3) — 현황판의 그 줄이 「미접속」→「진행 중」
common.publish(channel, group, 'mission.started', e, date, gid, uid, t)

-- 9. 시작함
return { 'OK', ts, '1', '0', 'IN_PROGRESS' }
