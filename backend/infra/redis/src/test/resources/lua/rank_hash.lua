-- 테스트 래퍼 — 그룹 Hash 에서 읽어(ranking.fromHash) 순위를 매기고 랭킹 JSON(ranking.encode)을 돌려준다.
-- KEYS[1] = 그룹 Hash · ARGV = 그룹원 uid 들.
return ranking.encode(ranking.rank(ranking.fromHash(KEYS[1], ARGV)))
