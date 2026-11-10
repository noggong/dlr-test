-- 공통 도우미 — 모든 기능 Lua 의 앞에 붙는다(LLD platform §1 · lib 모듈 규약).
-- `common` 은 로더(RedisScripts)가 앞에서 local 로 선언해 둔 자리다 — 여기서 local 을 다시 붙이지 않는다.
-- 그룹 상태 · 그룹원 칸 · XADD · SPUBLISH 도우미는 solve-signal 일감이 더한다(LLD solve-signal §1).
common = {}

-- 지금 시각(epoch µs) = Redis TIME + offsetUs(설정 game.clock-offset 을 µs 로 — HLD platform §8, 운영 0).
-- offsetUs 가 `-?숫자` 꼴의 십진 정수가 아니면(빈 값 · 1e3 · 0x10 포함) 스크립트 오류로 끝낸다(조용히 읽지 않는다).
-- 값은 배정밀도 숫자라 정확하지만 tostring 은 지수 표기를 낸다 — 문자열로 쓸 때는 string.format('%.0f', t).
function common.now(offsetUs)
  if type(offsetUs) ~= 'string' or not string.match(offsetUs, '^%-?%d+$') then
    error('offsetUs must be an integer: ' .. tostring(offsetUs))
  end
  local offset = tonumber(offsetUs)
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000000 + tonumber(t[2]) + offset
end
