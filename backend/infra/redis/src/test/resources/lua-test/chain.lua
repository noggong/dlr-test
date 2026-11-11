-- KEYS[1] 에 ARGV[1] 을 lib 두 개로 감싸 쓰고, 그 값과 정수 · 안쪽 배열을 돌려준다.
local v = alpha.wrap(ARGV[1])
redis.call('SET', KEYS[1], v)
return { v, 42, { 'in', 7 } }
