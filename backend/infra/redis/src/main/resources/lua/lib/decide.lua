-- 그룹 판정(HLD solve-signal §4.1 · LLD §3.3a) — `solve` · `fail` 공용.
-- `decide` 는 로더(RedisScripts)가 앞에서 local 로 선언해 둔 자리다 — 여기서 local 을 다시 붙이지 않는다.
decide = {}

-- 그날 그룹원 uid 목록 = 명단 Hash 의 `#` 로 시작하지 않는 칸(LLD game-session §2.2).
function decide.uids(membersKey)
  local uids = {}
  for _, f in ipairs(redis.call('HKEYS', membersKey)) do
    if string.sub(f, 1, 1) ~= '#' then uids[#uids + 1] = f end
  end
  return uids
end

-- 랭킹 JSON(LLD deadline-result §2.2) — 지금 그룹 Hash 로 다시 계산한다.
function decide.ranking(groupKey, uids)
  return ranking.encode(ranking.rank(ranking.fromHash(groupKey, uids)))
end

-- 전원이 완료(SUCCEEDED · CHEAT_FAILED)면 결과를 HSETNX 로 한 번만 쓴다. 이번에 썼으면 그 값, 아니면 nil.
-- version 은 올리지 않는다 — 부른 Lua 가 반영에서 한 번 올렸다(한 실행 +1).
function decide.run(groupKey, uids, t, deadlineAt)
  local all = true
  for _, uid in ipairs(uids) do
    local st = redis.call('HGET', groupKey, uid .. ':st')
    if st == 'CHEAT_FAILED' then
      all = false
    elseif st ~= 'SUCCEEDED' then
      return nil
    end
  end
  local result = all and 'SUCCEEDED' or 'FAILED_IMMEDIATE'
  if redis.call('HSETNX', groupKey, 'result', result) == 0 then return nil end
  redis.call('HSET', groupKey,
    'decidedAt', common.fmt(t),
    'ranking', decide.ranking(groupKey, uids),
    'rankingFinal', (t < deadlineAt) and '1' or '0')
  redis.call('HDEL', groupKey, 'pending')
  return result
end
