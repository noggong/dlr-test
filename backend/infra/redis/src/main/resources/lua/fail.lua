-- Lua `fail` — 부정 실패 신호(LLD solve-signal §3.3 · SPEC SS7 · SS11 · SS14). 위에서부터 보고 처음 걸린 것으로 끝낸다.
-- KEYS: 그룹 Hash · 명단 Hash · 버킷 Stream · 그룹 채널.
-- ARGV: uid · gid · date · openAt · deadlineAt · graceEndAt · offsetUs.
-- 반환: { 코드, 진행도, 그룹원 상태, 그룹 상태, version, t }.
local groupKey, membersKey, streamKey, channel = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local uid, gid, date = ARGV[1], ARGV[2], ARGV[3]
common.int(ARGV[4], 'openAt') -- 시작한 그룹원은 07:00 이후에만 있다 — 형식만 본다
local deadlineAt = common.int(ARGV[5], 'deadlineAt')
common.int(ARGV[6], 'graceEndAt')
local t = common.now(ARGV[7])

-- 0 · 0′ · 0″ 참가 — 그룹 Hash 를 읽지 않는다.
local size = redis.call('HGET', membersKey, '#size')
local function outside(code) return { code, -1, '', '', 0, t } end
if not size then return outside('NO_ROSTER') end
if redis.call('HEXISTS', membersKey, uid) == 0 then return outside('NOT_PARTICIPANT') end
if tonumber(size) < 2 then return outside('SOLO_GROUP') end

local m = redis.call('HMGET', groupKey, uid .. ':st', uid .. ':p', uid .. ':lid', 'version', 'result')
local st, lid, result = m[1], m[3], m[5]
if not st or not lid then error('fail: group hash has no member fields for ' .. uid .. ': ' .. groupKey) end
if not (m[2] and string.match(m[2], '^%-?%d$')) then error('fail: bad ' .. uid .. ':p: ' .. tostring(m[2])) end
local p = tonumber(m[2])
if p < -1 or p > 3 then error('fail: bad ' .. uid .. ':p: ' .. m[2]) end
local function reply(code)
  return { code, p, st, common.groupStatus(groupKey, t, deadlineAt), tonumber(m[4] or '0'), t }
end

if st == 'NOT_STARTED' then return reply('MISSION_NOT_STARTED') end                      -- 1 (D14)
-- 2 바꾸지 않고 지금 상태: 성공(D9) · 이미 부정 실패(멱등) · 마감 실패 · 08:00 이후(D17) · 결과 확정 뒤. 멱등을 시간 창보다 먼저 본다.
if st == 'SUCCEEDED' or st == 'CHEAT_FAILED' or st == 'FAILED' or t >= deadlineAt or result then
  return reply('REPLAY')
end

-- 결과를 쓸 실행이면 쓰기 전에 그룹원 칸 형식을 검사한다 — Lua 는 오류가 나도 앞의 쓰기를 되돌리지 않는다(LLD §3.1).
local uids = decide.uids(membersKey)
if decide.allDone(groupKey, uids, uid, 'CHEAT_FAILED') then ranking.fromHash(groupKey, uids) end

-- 3 반영 — 진행도 그대로(R-F4)
st = 'CHEAT_FAILED'
local at = common.fmt(t)
redis.call('HSET', groupKey, uid .. ':st', st, uid .. ':cf', at)
local version = redis.call('HINCRBY', groupKey, 'version', 1)

-- 4 그룹 판정
local decided = decide.run(groupKey, uids, t, deadlineAt)

-- 5 기록 · 전달
local v = common.fmt(version)
local streamId = common.xadd(streamKey, {
  'type', 'member.cheat_failed', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid, 'version', v, 'at', at,
  'progress', tostring(p), 'cheatFailedAt', at,
})
if decided then
  local d = redis.call('HMGET', groupKey, 'decidedAt', 'rankingFinal', 'ranking')
  streamId = common.xadd(streamKey, {
    'type', 'group.decided', 'date', date, 'gid', gid, 'uid', uid, 'lid', lid, 'version', v, 'at', at,
    'result', decided, 'decidedAt', d[1], 'memberCount', tostring(#uids), 'rankingFinal', d[2], 'ranking', d[3],
  })
end
common.publish(channel, groupKey, decided and 'group.decided' or 'member.cheat_failed', streamId, date, gid, uid, t)

return { 'OK', p, st, common.groupStatus(groupKey, t, deadlineAt), version, t }
