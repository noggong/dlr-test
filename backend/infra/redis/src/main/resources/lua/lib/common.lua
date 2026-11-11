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

-- ARGV 의 십진 정수(µs 시각 등)를 숫자로. `-?숫자` 꼴이 아니면 스크립트 오류(now 와 같은 태도).
function common.int(v, name)
  if type(v) ~= 'string' or not string.match(v, '^%-?%d+$') then
    error(name .. ' must be an integer: ' .. tostring(v))
  end
  return tonumber(v)
end

-- 숫자 → 십진 정수 문자열(지수 표기 없음). µs 시각 · version 을 Hash · Stream · JSON 에 쓸 때 쓴다.
function common.fmt(n)
  return string.format('%.0f', n)
end

-- 응답의 그룹 상태(SPEC SS14 · LLD solve-signal §3.1 출력 [4]).
function common.groupStatus(key, t, deadlineAt)
  local v = redis.call('HMGET', key, 'result', 'pending')
  if v[1] == 'SUCCEEDED' then return 'SUCCEEDED' end
  if v[1] then return 'FAILED' end
  if v[2] == '1' or t >= deadlineAt then return 'PENDING' end
  return 'IN_PROGRESS'
end

-- XADD key * — fields 는 평평한 { 이름, 값, … }(값은 문자열, 공통 칸 먼저 — HLD platform §4.2). 새 id 를 돌려준다.
function common.xadd(key, fields)
  return redis.call('XADD', key, '*', unpack(fields))
end

-- 그룹 채널 메시지 한 건(realtime-board LLD §2.3): 반영 뒤 그룹 Hash 전체를 싣는다. streamId · uid 가 nil 이면 null.
function common.publish(channel, groupKey, kind, streamId, date, gid, uid, t)
  local all = redis.call('HGETALL', groupKey)
  local hash = {}
  for i = 1, #all, 2 do hash[all[i]] = all[i + 1] end
  local msg = {
    type = kind, streamId = streamId or cjson.null, version = tonumber(hash.version or '0'),
    date = date, gid = gid, uid = uid or cjson.null, at = common.fmt(t), hash = hash,
  }
  return redis.call('SPUBLISH', channel, cjson.encode(msg))
end
