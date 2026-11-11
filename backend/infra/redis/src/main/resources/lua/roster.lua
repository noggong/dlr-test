-- 명단 만들기 — 없을 때만(LLD game-session §4.1). `worker: member` 와 api 요청이 겹쳐도 한 번만 만든다(ORACLE I4).
-- KEYS[1] 명단 Hash g:{b}:gid:D:members · KEYS[2] 그룹 Hash g:{b}:gid:D · KEYS[3] 그날 그룹 목록 day:{b}:D
-- ARGV[1] D · ARGV[2] gid · ARGV[3] 그룹명 · ARGV[4] 남은 ms(다음 날 08:00 KST − 앱 게임 시각 — Redis 는 game.clock-offset 을 모르므로 절대 시각을 쓰지 않는다) · ARGV[5] n · 이어서 uid₁, lid₁, …, uidₙ, lidₙ
--      · (재적재 꼬리 — 있을 때만, LLD platform §4.2) ARGV[6+2n] version · ARGV[7+2n] rerank(0 · 1) · ARGV[8+2n] m · 이어서 칸₁, 값₁, …, 칸ₘ, 값ₘ
-- 반환 { 'OK', created } — created 1 = 이번 호출이 만들었다, 0 = 이미 있었다(아무것도 바꾸지 않는다).
local members, group, day = KEYS[1], KEYS[2], KEYS[3]
local date, gid, name = ARGV[1], ARGV[2], ARGV[3]
local ttlMs = common.int(ARGV[4], 'ttlMs')
if ttlMs < 1 then error('roster: ttlMs must be positive: ' .. ARGV[4]) end
local n = common.int(ARGV[5], 'n')
if n < 0 then error('roster: n must not be negative: ' .. ARGV[5]) end

-- 0. 재적재 꼬리 검사 — 쓰기 전에(Lua 는 오류가 나도 앞의 쓰기를 되돌리지 않는다)
local version, rerank, restore = '0', '0', {}
if #ARGV ~= 5 + 2 * n then
  local base = 5 + 2 * n
  if #ARGV < base + 3 then error('roster: ARGV must have ' .. base .. ' items or a rehydrate tail: ' .. #ARGV) end
  version = ARGV[base + 1]
  common.int(version, 'version')
  rerank = ARGV[base + 2]
  if rerank ~= '0' and rerank ~= '1' then error('roster: rerank must be 0 or 1: ' .. tostring(rerank)) end
  local m = common.int(ARGV[base + 3], 'm')
  if m < 0 or #ARGV ~= base + 3 + 2 * m then error('roster: ARGV must have ' .. (base + 3 + 2 * m) .. ' items: ' .. #ARGV) end
  local uids = {}
  for i = 1, n do uids[ARGV[5 + 2 * i - 1]] = true end
  -- 칸마다 값 꼴(LLD platform §4.2) — 랭킹 다시 계산(ranking.fromHash)이 쓰기 뒤에 실패해 반쪽 Hash 가 남지 않게 여기서 거른다
  local us, bit, hex = '^%d+$', '^[01]$', '^[0-9a-f]+$'
  local states = { NOT_STARTED = true, IN_PROGRESS = true, SUCCEEDED = true, CHEAT_FAILED = true, FAILED = true }
  local results = { SUCCEEDED = true, FAILED_IMMEDIATE = true, FAILED_DEADLINE = true }
  local member = { p = '^%-?%d$', sa = us, late = bit, ls = us, pn = '^[123]$', pq = hex, cur = hex, cf = us }
  local group = { decidedAt = us, rankingFinal = bit }
  for i = 1, m do
    local field, value = ARGV[base + 3 + 2 * i - 1], ARGV[base + 3 + 2 * i]
    local uid, sub = string.match(field, '^(%d+):(%a+)$')
    local ok
    if field == 'result' then
      ok = results[value] == true
    elseif group[field] then
      ok = string.match(value, group[field]) ~= nil
    elseif uid ~= nil and uids[uid] and sub == 'st' then
      ok = states[value] == true
    elseif uid ~= nil and uids[uid] and member[sub] then
      ok = string.match(value, member[sub]) ~= nil and (sub ~= 'p' or (tonumber(value) >= -1 and tonumber(value) <= 3 and value ~= '-0'))
    else
      error('roster: rehydrate field not allowed: ' .. field)
    end
    if not ok then error('roster: rehydrate value not allowed: ' .. field .. '=' .. value) end
    restore[#restore + 1] = field
    restore[#restore + 1] = value
  end
end

-- 1. 이미 있음
if redis.call('EXISTS', members) == 1 then return { 'OK', 0 } end

-- 2. 명단 Hash — 0 · 1 명도 만든다(#size 가 있으면 「명단 확정됨」)
local roster = { '#size', common.fmt(n), '#name', name }
for i = 1, n do
  roster[#roster + 1] = ARGV[5 + 2 * i - 1]
  roster[#roster + 1] = ARGV[5 + 2 * i]
end
redis.call('HSET', members, unpack(roster))
redis.call('PEXPIRE', members, common.fmt(ttlMs))

-- 3. 2명 이상이면 그룹 Hash · 그날 그룹 목록. 그룹 Hash 가 이미 있으면(재적재가 먼저 올림) 진행도를 -1 로 덮지 않는다
if n >= 2 then
  if redis.call('EXISTS', group) == 0 then
    local fields = { 'date', date, 'gid', gid, 'size', common.fmt(n), 'version', '0' }
    for i = 1, n do
      local uid = ARGV[5 + 2 * i - 1]
      fields[#fields + 1] = uid .. ':lid'
      fields[#fields + 1] = ARGV[5 + 2 * i]
      fields[#fields + 1] = uid .. ':p'
      fields[#fields + 1] = '-1'
      fields[#fields + 1] = uid .. ':st'
      fields[#fields + 1] = 'NOT_STARTED'
    end
    redis.call('HSET', group, unpack(fields))
    -- 재적재: 덮을 칸 → version → 결과가 있으면 되살린 칸으로 랭킹을 다시 계산
    if #restore > 0 then redis.call('HSET', group, unpack(restore)) end
    redis.call('HSET', group, 'version', version)
    if rerank == '1' then redis.call('HSET', group, 'ranking', decide.ranking(group, decide.uids(members))) end
    redis.call('PEXPIRE', group, common.fmt(ttlMs))
  end
  redis.call('SADD', day, gid)
  redis.call('PEXPIRE', day, common.fmt(ttlMs))
end

-- 4. 만들었음
return { 'OK', 1 }
