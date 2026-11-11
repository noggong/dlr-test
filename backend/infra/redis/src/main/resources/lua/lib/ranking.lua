-- 랭킹 규칙(prd §6 · HLD deadline-result §4 · SPEC DR6 · DR7 · LLD deadline-result §4.2).
-- Kotlin `core/domain/ranking/Ranking.kt` 와 같은 구조다 — 한쪽을 고치면 다른 쪽도 고치고 ORACLE O1 ~ O3 을 다시 돌린다.
-- `ranking` 은 로더(RedisScripts)가 앞에서 local 로 선언해 둔 자리다 — 여기서 local 을 다시 붙이지 않는다.
--
-- 입력 한 줄 m: login · cheat(boolean, 상태 CHEAT_FAILED) · p(-1~3) · l(마지막 풀이 µs, 숫자 · 없으면 nil)
--               · n(패스 번호 · 없으면 nil) · late(boolean, 시작 전이면 false). p 가 1~3 이면 l 이 있어야 한다.
-- fromHash 로 만든 줄은 encode 에 쓰는 id(uid) · s(상태) · ls(l 의 원래 문자열)도 갖는다.
ranking = {}

-- 음수: a 가 위 · 양수: b 가 위 · 0: 동률. 앞 키가 같을 때만 다음 키를 본다.
function ranking.cmp(a, b)
  if a.cheat ~= b.cheat then return a.cheat and 1 or -1 end                    -- 0순위
  if a.cheat then return 0 end                                                  -- 부정 실패끼리 동률(끝)
  if a.p ~= b.p then return (a.p > b.p) and -1 or 1 end                         -- 1순위 진행도
  if a.p >= 1 then
    if a.l ~= b.l then return (a.l < b.l) and -1 or 1 end                       -- 2순위 도착 시각(µs)
    if (a.n == nil) ~= (b.n == nil) then return (a.n == nil) and -1 or 1 end    -- 3순위 패스 안 씀
    if a.n ~= nil and a.n ~= b.n then return (a.n > b.n) and -1 or 1 end        -- 둘 다 씀: 큰 번호
    return 0
  elseif a.p == 0 then
    if a.late ~= b.late then return a.late and 1 or -1 end                      -- 2′ 지각 아님
    if (a.n == nil) ~= (b.n == nil) then return (a.n == nil) and -1 or 1 end    -- 3′ 패스 안 씀
    return 0                                                                     -- 둘 다 씀: 동률
  end
  return 0                                                                       -- 진행도 -1 동률
end

-- 로그인 아이디 바이트 순(로케일 무관 — `<` 는 strcoll 이라 쓰지 않는다). Kotlin 은 String.compareTo(아이디가 ASCII 라 같다).
function ranking.bytecmp(x, y)
  local nx, ny = #x, #y
  for i = 1, math.min(nx, ny) do
    local bx, by = string.byte(x, i), string.byte(y, i)
    if bx ~= by then return (bx < by) and -1 or 1 end
  end
  if nx == ny then return 0 end
  return (nx < ny) and -1 or 1
end

-- members 를 표시 순서로 정렬하고 경쟁 순위(1, 1, 3)를 m.r 에 붙여 같은 표를 돌려준다.
-- table.sort 는 불안정하지만 cmp 다음에 로그인 아이디로 끝까지 순서를 정하므로 결과가 하나다.
function ranking.rank(members)
  table.sort(members, function(a, b)
    local c = ranking.cmp(a, b)
    if c ~= 0 then return c < 0 end
    return ranking.bytecmp(a.login, b.login) < 0
  end)
  for i, m in ipairs(members) do
    if i > 1 and ranking.cmp(members[i - 1], m) == 0 then m.r = members[i - 1].r else m.r = i end
  end
  return members
end

-- 그룹 Hash(LLD §2.2)에서 그룹원 uids 의 랭킹 입력을 읽는다. 순위는 붙이지 않는다 — rank 에 넘긴다.
-- 칸 형식이 어긋나면 조용히 읽지 않고 스크립트 오류로 끝낸다(common.now 와 같은 태도):
-- lid · st 필수, p 는 -1~3 정수, ls 는 µs 십진 정수(지수 표기 금지 — string.format('%.0f', t) 로 쓴다),
-- pn 은 1~3, late 는 1 · 0. p 가 1~3 이면 ls 가 있어야 한다(Kotlin RankingKey 불변식과 같다).
function ranking.fromHash(key, uids)
  local members = {}
  for i, uid in ipairs(uids) do
    local v = redis.call('HMGET', key, uid .. ':lid', uid .. ':st', uid .. ':p', uid .. ':late', uid .. ':ls', uid .. ':pn')
    local function bad(field) error('ranking.fromHash: bad ' .. field .. ' for ' .. uid .. ': ' .. tostring(v[field])) end
    if not v[1] then bad(1) end
    if not v[2] then bad(2) end
    if not (v[3] and string.match(v[3], '^%-?%d$')) then bad(3) end
    local p = tonumber(v[3])
    if p < -1 or p > 3 then bad(3) end
    if v[4] and v[4] ~= '1' and v[4] ~= '0' then bad(4) end
    if v[5] and not string.match(v[5], '^%d+$') then bad(5) end
    if v[6] and not string.match(v[6], '^[123]$') then bad(6) end
    local ls = v[5] or nil
    if p >= 1 and ls == nil then
      error('ranking.fromHash: progress >= 1 needs ls: ' .. uid)
    end
    members[i] = {
      id = uid, login = v[1], s = v[2], cheat = (v[2] == 'CHEAT_FAILED'), p = p,
      ls = ls, l = ls and tonumber(ls) or nil, n = v[6] and tonumber(v[6]) or nil,
      late = (v[4] == '1'),
    }
  end
  return members
end

-- fromHash → rank 를 거친 members 를 랭킹 JSON(LLD §2.2 — 순위 오름차순 · 같은 순위는 u 바이트 순)으로.
-- 시각 l 은 cjson 이 14자리로 줄이므로 Hash 의 문자열 그대로 싣는다. 객체 안 키 순서는 정하지 않는다 —
-- 쓰는 쪽은 JSON 으로 읽어 비교한다(문자열 비교 금지). fromHash 가 만든 줄(id · s · ls)만 받는다.
function ranking.encode(members)
  if #members == 0 then return '[]' end
  local rows = {}
  for i, m in ipairs(members) do
    if m.id == nil or m.s == nil or m.r == nil or (m.l ~= nil and m.ls == nil) then
      error('ranking.encode: needs fromHash + rank rows: ' .. tostring(m.login))
    end
    rows[i] = {
      id = m.id, u = m.login, r = m.r, p = m.p, s = m.s,
      l = m.ls or cjson.null, n = m.n or cjson.null,
    }
  end
  return cjson.encode(rows)
end
