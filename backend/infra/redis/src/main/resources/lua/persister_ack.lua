-- persister ACK + 트림(LLD platform §3.1).
-- KEYS[1] ev:{<b>} · ARGV[1] 그룹 · ARGV[2..] ACK 할 id(오름차순 — 마지막이 가장 크다)
-- 트림: XTRIM MINID m — m = 마지막 id 와 그룹의 가장 작은 pending id 중 작은 것. ACK 안 한 메시지와 그 뒤는 남는다.
-- 반환: {ACK 한 수}
local function less(a, b)
  local am, as = string.match(a, '^(%d+)-(%d+)$')
  local bm, bs = string.match(b, '^(%d+)-(%d+)$')
  am, as, bm, bs = tonumber(am), tonumber(as), tonumber(bm), tonumber(bs)
  if am ~= bm then return am < bm end
  return as < bs
end

local acked = redis.call('XACK', KEYS[1], ARGV[1], unpack(ARGV, 2))
local minId = ARGV[#ARGV]
local summary = redis.call('XPENDING', KEYS[1], ARGV[1])
if summary[1] > 0 and summary[2] and less(summary[2], minId) then minId = summary[2] end
redis.call('XTRIM', KEYS[1], 'MINID', minId)
return {acked}
