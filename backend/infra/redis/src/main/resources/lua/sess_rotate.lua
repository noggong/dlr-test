-- refresh 회전 — 비교 후 교체(auth-signup LLD §2.2).
-- KEYS[1] = sess:{<uid>} · ARGV[1] = 요청 refresh token 의 jti · ARGV[2] = 새 jti · ARGV[3] = TTL 초
-- 반환 1 = 회전함 · 0 = 거부(이미 회전됨 · 로그아웃 · 만료)
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
return 1
