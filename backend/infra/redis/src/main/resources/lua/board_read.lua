-- 현황판 읽기(LLD realtime-board §3 · SPEC RB1 · RB2 · RB3) — 조회 · 티켓 · 스트림이 같은 판정 순서로 쓴다.
-- KEYS[1] 명단 Hash g:{b}:gid:date:members · KEYS[2] 그룹 Hash g:{b}:gid:date
-- ARGV[1] uid · ARGV[2] 창 시작 µs · ARGV[3] 창 끝 µs(미만까지) · ARGV[4] offsetUs · ARGV[5] 모드 board · ticket · stream
-- ARGV[6] 최소 그룹원 수(game.min-group-size)
-- 반환 { 'ERR', code } 또는 { 'OK', now } (ticket) · { 'OK', now, HGETALL… } — now 는 µs 십진 문자열.
-- 처음 걸린 판정으로 끝낸다. 아무 키도 쓰지 않는다.
local roster, group = KEYS[1], KEYS[2]
local uid, mode = ARGV[1], ARGV[5]
if mode ~= 'board' and mode ~= 'ticket' and mode ~= 'stream' then
  error('board_read: mode must be board, ticket or stream: ' .. tostring(mode))
end
local from = common.int(ARGV[2], 'board_read: window start')
local to = common.int(ARGV[3], 'board_read: window end')
local minSize = common.int(ARGV[6], 'board_read: min group size')
local t = common.now(ARGV[4])

-- 1. 시간 창
if t < from or t >= to then return { 'ERR', 'GAME_NOT_OPEN' } end
-- 2. 명단 확정 전
local size = redis.call('HGET', roster, '#size')
if not size then return { 'ERR', 'GAME_NOT_OPEN' } end
-- 3. 그날 그룹원 아님(그날 06:50 부터 가입 — prd D24)
if redis.call('HEXISTS', roster, uid) == 0 then return { 'ERR', 'NOT_PARTICIPANT' } end
-- 4. 1명 그룹
if common.int(size, 'board_read: #size') < minSize then return { 'ERR', 'SOLO_GROUP' } end
-- 5. 티켓은 그룹 Hash 를 읽지 않는다
local now = common.fmt(t)
if mode == 'ticket' then return { 'OK', now } end
-- 6. 그룹 Hash 없음(재적재 전)
if redis.call('EXISTS', group) == 0 then return { 'ERR', 'GAME_NOT_OPEN' } end
-- 7.
local reply = { 'OK', now }
for _, v in ipairs(redis.call('HGETALL', group)) do reply[#reply + 1] = v end
return reply
