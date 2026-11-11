-- ORACLE platform O9 탐침: common.now(offsetUs) 와 그 직후 TIME(µs)을 함께 돌려준다.
local now = common.now(ARGV[1])
local t = redis.call('TIME')
return { now, tonumber(t[1]) * 1000000 + tonumber(t[2]) }
