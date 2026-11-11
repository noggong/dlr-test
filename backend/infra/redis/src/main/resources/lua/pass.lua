-- Lua `pass` — 패스 신호(LLD solve-signal §3.2 · SPEC SS8 ~ SS10 · SS14). 위에서부터 보고 처음 걸린 것으로 끝낸다.
-- KEYS: 그룹 Hash · 명단 Hash · 버킷 Stream · 그룹 채널.
-- ARGV: uid · gid · date · openAt · deadlineAt · graceEndAt · offsetUs · questionNo · questionId.
-- 반환: { 코드, 진행도, 그룹원 상태, 그룹 상태, version, t, 패스 번호(없으면 0) }.
-- 그룹 판정은 없다 — 패스는 그룹원 상태를 바꾸지 않는다. 결과가 난 그룹은 4 에서 거부되므로 랭킹을 다시 쓸 일도 없다.
local groupKey, membersKey, streamKey, channel = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local uid, gid, date = ARGV[1], ARGV[2], ARGV[3]
local openAt = common.int(ARGV[4], 'openAt')
local deadlineAt = common.int(ARGV[5], 'deadlineAt')
common.int(ARGV[6], 'graceEndAt') -- 패스는 유예가 없다(SS10) — 형식만 본다
local t = common.now(ARGV[7])
if not string.match(ARGV[8], '^[123]$') then error('questionNo must be 1~3: ' .. tostring(ARGV[8])) end
local questionNo, questionId = tonumber(ARGV[8]), ARGV[9]

-- 0 · 0′ · 0″ 참가 — 그룹 Hash 를 읽지 않는다.
local size = redis.call('HGET', membersKey, '#size')
local function outside(code) return { code, -1, '', '', 0, t, 0 } end
if not size then return outside('NO_ROSTER') end
if redis.call('HEXISTS', membersKey, uid) == 0 then return outside('NOT_PARTICIPANT') end
if tonumber(size) < 2 then return outside('SOLO_GROUP') end

local m = redis.call('HMGET', groupKey, uid .. ':st', uid .. ':p', uid .. ':cur', uid .. ':lid', 'version',
  uid .. ':pn', uid .. ':pq', 'result')
local st, cur, lid, pn, pq, result = m[1], m[3], m[4], m[6], m[7], m[8]
if not st or not lid then error('pass: group hash has no member fields for ' .. uid .. ': ' .. groupKey) end
if not (m[2] and string.match(m[2], '^%-?%d$')) then error('pass: bad ' .. uid .. ':p: ' .. tostring(m[2])) end
local p = tonumber(m[2])
if p < -1 or p > 3 then error('pass: bad ' .. uid .. ':p: ' .. m[2]) end
if pn and not string.match(pn, '^[123]$') then error('pass: bad ' .. uid .. ':pn: ' .. pn) end
local function reply(code)
  return { code, p, st, common.groupStatus(groupKey, t, deadlineAt), tonumber(m[5] or '0'), t, tonumber(pn or '0') }
end

if st == 'NOT_STARTED' then return reply('MISSION_NOT_STARTED') end                      -- 1
if pn and tonumber(pn) == questionNo and pq == questionId then return reply('REPLAY') end -- 2a 멱등(시간 창보다 먼저)
if pn then return reply('PASS_ALREADY_USED') end                                          -- 2b 하루 1번
if st == 'SUCCEEDED' or st == 'CHEAT_FAILED' then return reply('MISSION_ALREADY_FINISHED') end -- 3
if t < openAt then return reply('GAME_NOT_OPEN') end                                      -- 4 유예 없음
if t >= deadlineAt or result then return reply('SUBMISSION_CLOSED') end
if questionNo ~= p + 1 then return reply('QUESTION_OUT_OF_ORDER') end                     -- 5
if not cur or cur ~= questionId then return reply('QUESTION_MISMATCH') end

-- 6 반영 · 기록 · 전달
pn = ARGV[8]
redis.call('HSET', groupKey, uid .. ':pn', pn, uid .. ':pq', questionId)
redis.call('HDEL', groupKey, uid .. ':cur')
local version = redis.call('HINCRBY', groupKey, 'version', 1)
local at = common.fmt(t)
local streamId = common.xadd(streamKey, {
  'type', 'pass.used', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid, 'version', common.fmt(version), 'at', at,
  'questionNo', pn, 'questionId', questionId,
})
common.publish(channel, groupKey, 'pass.used', streamId, date, gid, uid, t)

return { 'OK', p, st, common.groupStatus(groupKey, t, deadlineAt), version, t, tonumber(pn) }
