-- 명단 만들기 — 없을 때만(LLD game-session §4.1). `worker: member` 와 api 요청이 겹쳐도 한 번만 만든다(ORACLE I4).
-- KEYS[1] 명단 Hash g:{b}:gid:D:members · KEYS[2] 그룹 Hash g:{b}:gid:D · KEYS[3] 그날 그룹 목록 day:{b}:D
-- ARGV[1] D · ARGV[2] gid · ARGV[3] 그룹명 · ARGV[4] 만료 epoch 초(다음 날 08:00 KST) · ARGV[5] n · 이어서 uid₁, lid₁, …, uidₙ, lidₙ
-- 반환 { 'OK', created } — created 1 = 이번 호출이 만들었다, 0 = 이미 있었다(아무것도 바꾸지 않는다).
local members, group, day = KEYS[1], KEYS[2], KEYS[3]
local date, gid, name = ARGV[1], ARGV[2], ARGV[3]
local expireAt = common.int(ARGV[4], 'expireAt')
local n = common.int(ARGV[5], 'n')
if #ARGV ~= 5 + 2 * n then error('roster: ARGV must have ' .. (5 + 2 * n) .. ' items: ' .. #ARGV) end

-- 1. 이미 있음
if redis.call('EXISTS', members) == 1 then return { 'OK', 0 } end

-- 2. 명단 Hash — 0 · 1 명도 만든다(#size 가 있으면 「명단 확정됨」)
local roster = { '#size', common.fmt(n), '#name', name }
for i = 1, n do
  roster[#roster + 1] = ARGV[5 + 2 * i - 1]
  roster[#roster + 1] = ARGV[5 + 2 * i]
end
redis.call('HSET', members, unpack(roster))
redis.call('EXPIREAT', members, common.fmt(expireAt))

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
    redis.call('EXPIREAT', group, common.fmt(expireAt))
  end
  redis.call('SADD', day, gid)
  redis.call('EXPIREAT', day, common.fmt(expireAt))
end

-- 4. 만들었음
return { 'OK', 1 }
