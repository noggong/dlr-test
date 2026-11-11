-- persister 회수(LLD platform §3.1) — XAUTOCLAIM 한 번.
-- KEYS[1] ev:{<b>} · ARGV[1] 그룹 · ARGV[2] 소비자 · ARGV[3] min-idle ms · ARGV[4] 시작 커서 · ARGV[5] COUNT
-- 반환: {다음 커서, {{id, {칸, 값, …}}, …}} — 커서 '0-0' 이면 끝. 지워진 항목은 XAUTOCLAIM 이 PEL 에서 빼고 돌려주지 않는다.
local r = redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4], 'COUNT', ARGV[5])
local out = {}
for _, e in ipairs(r[2]) do
  out[#out + 1] = {e[1], e[2] or {}}
end
return {r[1], out}
