-- 결과 캐시 날짜 비교 쓰기(LLD deadline-result §4.3) — persister 가 `group.decided` · `group.ranking_final` 커밋 뒤 부른다.
-- KEYS[1] = result:{<gid>} · ARGV = 결과 날짜(YYYY-MM-DD) · rankingFinal(1 · 0) · §2.4 JSON · TTL 초.
-- 반환 1 = 썼다 · 0 = 더 새 값이 있어 쓰지 않았다. 캐시 값은 Kotlin 이 쓴 JSON 이고 여기서는 date · rankingFinal 만 본다.
local date, final, json, ttl = ARGV[1], ARGV[2], ARGV[3], tonumber(ARGV[4])
if not string.match(date, '^%d%d%d%d%-%d%d%-%d%d$') then error('result_cache_put: date 는 YYYY-MM-DD: ' .. tostring(date)) end
if final ~= '0' and final ~= '1' then error('result_cache_put: rankingFinal 은 0 · 1: ' .. tostring(final)) end
if not ttl or ttl < 1 then error('result_cache_put: ttl 은 1 이상: ' .. tostring(ARGV[4])) end

local cur = redis.call('GET', KEYS[1])
if cur then
  -- 읽지 못하는 값(형식이 바뀜)은 없는 것으로 보고 덮는다 — 조회도 그런 값은 버린다(LLD §5)
  local ok, old = pcall(cjson.decode, cur)
  if ok and type(old) == 'table' and type(old.date) == 'string' then
    -- 2. 옛 결과의 재처리
    if old.date > date then return 0 end
    -- 3. 같은 날의 확정 랭킹을 잠정으로 덮지 않는다
    if old.date == date and old.rankingFinal == true and final == '0' then return 0 end
  end
end
-- 4 · 5. 다음 게임 결과는 TTL 이 남아 있어도 덮는다
redis.call('SET', KEYS[1], json, 'EX', ttl)
return 1
