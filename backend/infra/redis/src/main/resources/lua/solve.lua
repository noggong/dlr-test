-- Lua `solve` — 풀이 신호(LLD solve-signal §3.1 · SPEC SS1 ~ SS7 · SS12 · SS14). 위에서부터 보고 처음 걸린 것으로 끝낸다.
-- KEYS: 그룹 Hash · 명단 Hash · 버킷 Stream · 그룹 채널.
-- ARGV: uid · gid · date · openAt · deadlineAt · graceEndAt · offsetUs · questionNo · questionId · clickedAt · successTime.
-- 반환: { 코드, 진행도, 그룹원 상태, 그룹 상태, version, t }.
local groupKey, membersKey, streamKey, channel = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local uid, gid, date = ARGV[1], ARGV[2], ARGV[3]
local openAt = common.int(ARGV[4], 'openAt')
local deadlineAt = common.int(ARGV[5], 'deadlineAt')
local graceEndAt = common.int(ARGV[6], 'graceEndAt')
local t = common.now(ARGV[7])
if not string.match(ARGV[8], '^[123]$') then error('questionNo must be 1~3: ' .. tostring(ARGV[8])) end
local questionNo, questionId = tonumber(ARGV[8]), ARGV[9]
local clickedAt = common.int(ARGV[10], 'clickedAt')
local successTime = ARGV[11]

-- 0 · 0′ · 0″ 참가 — 그룹 Hash 를 읽지 않는다.
local size = redis.call('HGET', membersKey, '#size')
local function outside(code) return { code, -1, '', '', 0, t } end
if not size then return outside('NO_ROSTER') end
if redis.call('HEXISTS', membersKey, uid) == 0 then return outside('NOT_PARTICIPANT') end
if tonumber(size) < 2 then return outside('SOLO_GROUP') end

local m = redis.call('HMGET', groupKey, uid .. ':st', uid .. ':p', uid .. ':cur', uid .. ':lid', 'version')
local st, cur, lid = m[1], m[3], m[4]
if not st or not lid then error('solve: group hash has no member fields for ' .. uid .. ': ' .. groupKey) end
if not (m[2] and string.match(m[2], '^%-?%d$')) then error('solve: bad ' .. uid .. ':p: ' .. tostring(m[2])) end
local p = tonumber(m[2])
if p < -1 or p > 3 then error('solve: bad ' .. uid .. ':p: ' .. m[2]) end
local function reply(code)
  return { code, p, st, common.groupStatus(groupKey, t, deadlineAt), tonumber(m[5] or '0'), t }
end

if st == 'NOT_STARTED' then return reply('MISSION_NOT_STARTED') end                     -- 1
if questionNo <= p then return reply('REPLAY') end                                       -- 2 멱등(시간 창보다 먼저)
if st == 'CHEAT_FAILED' then return reply('MISSION_ALREADY_FINISHED') end                -- 3
if t < openAt then return reply('GAME_NOT_OPEN') end                                     -- 4a
if t >= graceEndAt or (t >= deadlineAt and clickedAt >= deadlineAt) then                 -- 4c · 4b(D1)
  return reply('SUBMISSION_CLOSED')
end
if questionNo > p + 1 then return reply('QUESTION_OUT_OF_ORDER') end                     -- 5
if not cur or cur ~= questionId then return reply('QUESTION_MISMATCH') end               -- 6

-- 랭킹을 쓸 실행이면 쓰기 전에 그룹원 칸 형식을 검사한다 — Lua 는 오류가 나도 앞의 쓰기를 되돌리지 않는다(LLD §3.1).
-- 반영 뒤 상태: FAILED(08:00 확정 그룹의 유예 풀이, D17)는 상태를 바꾸지 않는다.
local newP = p + 1
local newSt = (st == 'IN_PROGRESS' and newP == 3) and 'SUCCEEDED' or st
local uids = decide.uids(membersKey)
local g = redis.call('HMGET', groupKey, 'result', 'rankingFinal')
local rerank = g[1] and g[2] ~= '1'
if rerank or (not g[1] and decide.allDone(groupKey, uids, uid, newSt)) then
  ranking.fromHash(groupKey, uids)
end

-- 7 반영
p, st = newP, newSt
local at = common.fmt(t)
redis.call('HSET', groupKey, uid .. ':p', tostring(p), uid .. ':ls', at, uid .. ':st', st)
redis.call('HDEL', groupKey, uid .. ':cur')
local version = redis.call('HINCRBY', groupKey, 'version', 1)

-- 8 그룹 판정 · 확정 뒤 유예 반영이면 랭킹만 다시
local decided = nil
if not g[1] then
  decided = decide.run(groupKey, uids, t, deadlineAt)
elseif rerank then
  redis.call('HSET', groupKey, 'ranking', decide.ranking(groupKey, uids))
end

-- 9 기록 · 전달
local v = common.fmt(version)
local streamId = common.xadd(streamKey, {
  'type', 'solve.applied', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid, 'version', v, 'at', at,
  'questionNo', ARGV[8], 'questionId', questionId, 'successTime', successTime, 'clickedAt', common.fmt(clickedAt),
  'arrivedAt', at, 'progress', tostring(p), 'status', st, 'grace', (t >= deadlineAt) and '1' or '0',
})
if decided then
  local d = redis.call('HMGET', groupKey, 'decidedAt', 'rankingFinal', 'ranking')
  streamId = common.xadd(streamKey, {
    'type', 'group.decided', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid, 'version', v, 'at', at,
    'result', decided, 'decidedAt', d[1], 'memberCount', tostring(#uids), 'rankingFinal', d[2], 'ranking', d[3],
  })
end
common.publish(channel, groupKey, decided and 'group.decided' or 'solve.applied', streamId, date, gid, uid, t)

return { 'OK', p, st, common.groupStatus(groupKey, t, deadlineAt), version, t }
