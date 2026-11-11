-- 로그아웃 — 비교 후 삭제(auth-signup LLD §2.2).
-- KEYS[1] = sess:{<uid>} · ARGV[1] = 요청 refresh token 의 jti
-- 반환 1 = 지움 · 0 = 그대로(다른 세션 · 이미 없음)
if redis.call('GET', KEYS[1]) == ARGV[1] then
  redis.call('DEL', KEYS[1])
  return 1
end
return 0
