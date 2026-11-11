-- 테스트 래퍼(ORACLE deadline-result O2 · O3 · O4) — lib/ranking.lua 의 rank 만 부른다. 키를 건드리지 않는다.
-- ARGV: 그룹을 이어 붙인다. 그룹마다 인원 k, 그 뒤 그룹원마다 6칸
--       login · cheat(1|0) · p · l(µs 정수 · 없으면 '') · n(없으면 '') · late(1|0).
-- 반환: 그룹마다 표시 순서대로 { login1, r1, login2, r2, … }.
local out = {}
local i = 1
while i <= #ARGV do
  local k = tonumber(ARGV[i])
  if k == nil then error('rank_only: bad member count at ARGV[' .. i .. ']') end
  i = i + 1
  local members = {}
  for j = 1, k do
    local l, n = ARGV[i + 3], ARGV[i + 4]
    members[j] = {
      login = ARGV[i], cheat = (ARGV[i + 1] == '1'), p = tonumber(ARGV[i + 2]),
      l = (l ~= '') and tonumber(l) or nil, n = (n ~= '') and tonumber(n) or nil,
      late = (ARGV[i + 5] == '1'),
    }
    if members[j].p == nil or (l ~= '' and members[j].l == nil) or (n ~= '' and members[j].n == nil) then
      error('rank_only: bad member at ARGV[' .. i .. ']')
    end
    i = i + 6
  end
  local row = {}
  for _, m in ipairs(ranking.rank(members)) do
    row[#row + 1] = m.login
    row[#row + 1] = m.r
  end
  out[#out + 1] = row
end
return out
