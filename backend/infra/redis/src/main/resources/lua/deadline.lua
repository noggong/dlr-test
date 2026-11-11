-- 마감 판정(LLD deadline-result §4.1 · SPEC DR3 · DR4 · DR5) — 그룹 하나를 08:00 1차 · 08:00:03 2차로 처리한다.
-- KEYS[1] 그룹 Hash · KEYS[2] 명단 Hash · KEYS[3] 이벤트 Stream ev:{b}
-- ARGV[1] phase(1 · 2) · ARGV[2] date · ARGV[3] gid · ARGV[4] deadlineUs · ARGV[5] graceEndUs
-- ARGV[6] 채널 ch:{b}:gid · ARGV[7] offsetUs
-- 반환 { code, version } — code: EARLY · NO_GROUP · SKIPPED · PENDING · DECIDED · DECIDED_FINAL · FINALIZED.
-- 같은 단계를 다시 돌리면 PENDING(그대로) · SKIPPED 로 끝나고 version · Stream · 채널에 아무것도 더하지 않는다.
local group, roster, events = KEYS[1], KEYS[2], KEYS[3]
local phase, date, gid = ARGV[1], ARGV[2], ARGV[3]
local channel = ARGV[6]

local function integer(name, value)
  if type(value) ~= 'string' or not string.match(value, '^%-?%d+$') then
    error('deadline: ' .. name .. ' must be an integer: ' .. tostring(value))
  end
  return tonumber(value)
end

if phase ~= '1' and phase ~= '2' then error('deadline: phase must be 1 or 2: ' .. tostring(phase)) end
local deadlineUs = integer('deadlineUs', ARGV[4])
local graceEndUs = integer('graceEndUs', ARGV[5])
local t = common.now(ARGV[7])
local at = string.format('%.0f', t)

-- 1. 시각 전
if t < deadlineUs or (phase == '2' and t < graceEndUs) then return { 'EARLY', 0 } end
-- 2. 그룹 Hash 없음(1명 그룹 · 만료) — 키를 만들지 않는다
if redis.call('EXISTS', group) == 0 then return { 'NO_GROUP', 0 } end
-- 3. 08:00:03 뒤에 처리되면 phase 와 무관하게 한 번에 확정
local final = t >= graceEndUs

local state = redis.call('HMGET', group, 'rankingFinal', 'result', 'pending', 'version')
local current = integer('version', state[4] or '0')

-- 4. 랭킹까지 확정됨
if state[1] == '1' then return { 'SKIPPED', current } end

-- 그룹원 = 명단 Hash 의 # 로 시작하지 않는 필드
local uids = {}
for _, field in ipairs(redis.call('HKEYS', roster)) do
  if string.sub(field, 1, 1) ~= '#' then uids[#uids + 1] = field end
end
-- 그룹 Hash 가 있는데 그룹원이 없으면(명단 Hash 없음) 조용히 빈 랭킹으로 확정하지 않는다
if #uids == 0 then error('deadline: no members in ' .. roster) end
table.sort(uids)

-- 채널 메시지(realtime-board LLD §2.3) — 반영 뒤 그룹 Hash 전체를 싣는다.
local function publish(kind, streamId, v)
  local flat = redis.call('HGETALL', group)
  local hash = {}
  for i = 1, #flat, 2 do hash[flat[i]] = flat[i + 1] end
  redis.call('SPUBLISH', channel, cjson.encode({
    type = kind, streamId = streamId or cjson.null, version = v, date = date, gid = gid,
    uid = cjson.null, at = at, hash = hash,
  }))
end

-- 랭킹 JSON(LLD §2.2) — 상태를 바꾼 뒤에 읽어야 s 가 맞다.
local function rankingJson() return ranking.encode(ranking.rank(ranking.fromHash(group, uids))) end

if not state[2] then
  -- 5.1 미룸: 3번 문제를 푸는 중(IN_PROGRESS · 진행도 2 · cur 있음)인 그룹원이 있으면 08:00:03 까지
  if not final then
    for _, uid in ipairs(uids) do
      local m = redis.call('HMGET', group, uid .. ':st', uid .. ':p', uid .. ':cur')
      if m[1] == 'IN_PROGRESS' and m[2] == '2' and m[3] and m[3] ~= '' then
        if state[3] == '1' then return { 'PENDING', current } end
        redis.call('HSET', group, 'pending', '1')
        local v = redis.call('HINCRBY', group, 'version', 1)
        publish('group.pending', nil, v)
        return { 'PENDING', v }
      end
    end
  end
  -- 5.2 확정: 미완료 그룹원 FAILED → 결과 · 랭킹
  -- Lua 오류는 앞서 쓴 것을 되돌리지 않으므로 오류가 날 수 있는 일(칸 형식 검사 · HSETNX)을 쓰기 전에 한다.
  ranking.fromHash(group, uids)
  -- 결과는 한 번만(NFR-2) — 이 분기는 result 가 없을 때만 오지만 HSETNX 가 실패하면 아무것도 내지 않고 끝낸다
  if redis.call('HSETNX', group, 'result', 'FAILED_DEADLINE') == 0 then error('deadline: result already set') end
  local result = 'FAILED_DEADLINE'
  for _, uid in ipairs(uids) do
    local st = redis.call('HGET', group, uid .. ':st')
    if st == 'NOT_STARTED' or st == 'IN_PROGRESS' then redis.call('HSET', group, uid .. ':st', 'FAILED') end
  end
  local json = rankingJson()
  local rankingFinal = final and '1' or '0'
  redis.call('HSET', group, 'decidedAt', at, 'ranking', json, 'rankingFinal', rankingFinal)
  redis.call('HDEL', group, 'pending')
  local v = redis.call('HINCRBY', group, 'version', 1)
  local id = redis.call('XADD', events, '*',
    'type', 'group.decided', 'date', date, 'gid', gid, 'uid', '', 'lid', '', 'version', string.format('%.0f', v), 'at', at,
    'result', result, 'decidedAt', at, 'rankingFinal', rankingFinal, 'memberCount', string.format('%.0f', #uids), 'ranking', json)
  publish('group.decided', id, v)
  return { final and 'DECIDED_FINAL' or 'DECIDED', v }
end

-- 6.1 08:00 전 확정 · 유예 중 확정된 미룬 그룹의 1차 재실행
if not final then return { 'SKIPPED', current } end

-- 6.2 랭킹 확정 — 상태 · 결과 · decidedAt 은 그대로
local json = rankingJson()
redis.call('HSET', group, 'ranking', json, 'rankingFinal', '1')
redis.call('HDEL', group, 'pending') -- 결과를 쓴 solve 가 지웠어야 하지만 I4(pending · result 공존 없음)를 여기서도 지킨다
local v = redis.call('HINCRBY', group, 'version', 1)
local decidedAt = redis.call('HGET', group, 'decidedAt') or ''
local id = redis.call('XADD', events, '*',
  'type', 'group.ranking_final', 'date', date, 'gid', gid, 'uid', '', 'lid', '', 'version', string.format('%.0f', v), 'at', at,
  'result', state[2], 'decidedAt', decidedAt, 'rankingFinal', '1', 'ranking', json)
publish('group.ranking_final', id, v)
return { 'FINALIZED', v }
