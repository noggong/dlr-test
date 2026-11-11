-- persister consumer group 만들기(LLD platform §3.1) — 이미 있으면 그대로.
-- KEYS[1] ev:{<b>} · ARGV[1] 그룹 이름
-- 반환: {1} 새로 만듦 · {0} 이미 있음
local r = redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
if type(r) == 'table' and r.err then
  if string.find(r.err, 'BUSYGROUP', 1, true) then return {0} end
  return redis.error_reply(r.err)
end
return {1}
