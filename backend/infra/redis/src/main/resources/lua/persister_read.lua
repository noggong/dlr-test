-- persister 읽기(LLD platform §3.1) — BLOCK 없이 XREADGROUP 한 번.
-- KEYS[1] ev:{<b>} · ARGV[1] 그룹 · ARGV[2] 소비자 · ARGV[3] COUNT · ARGV[4] 시작 id('>' = 새 메시지, '0' = 자기 PEL)
-- 반환: {{id, {칸, 값, …}}, …} — id 순. 지워진 항목(PEL 에만 남은 id)은 칸 목록이 비어 있다.
local r = redis.call('XREADGROUP', 'GROUP', ARGV[1], ARGV[2], 'COUNT', ARGV[3], 'STREAMS', KEYS[1], ARGV[4])
local out = {}
if not r then return out end
for _, e in ipairs(r[1][2]) do
  out[#out + 1] = {e[1], e[2] or {}}
end
return out
