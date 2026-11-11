package com.delightroom.mission.infra.rdb

/**
 * 스키마 잠금(LLD platform §4.3 · SPEC platform P8) — 스키마를 바꾸는 길(`api` 의 schema.sql · `worker` 의 파티션 Q9)이 먼저 잡는
 * PostgreSQL advisory lock 키. 한 번에 한 길만 DDL 을 돌아 잠금 순서가 엇갈리지 않는다(빈 DB 동시 기동의 deadlock `40P01` · 카탈로그 중복 `23505`).
 * schema.sql 첫 · 마지막 문장의 숫자와 같아야 한다(ORACLE platform O36 이 견준다).
 */
object SchemaLock {
    /** ASCII `MISS` — 이 DB 에서 다른 용도로 쓰지 않는다. */
    const val KEY: Long = 1_296_651_091L
}
