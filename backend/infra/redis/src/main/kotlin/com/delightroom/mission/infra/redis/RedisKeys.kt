package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import java.time.LocalDate

/**
 * Redis 키 이름 — HLD platform §4.1 표를 글자 그대로 만든다(LLD platform §1).
 *
 * 한 그룹의 Lua 가 만지는 키(그룹 Hash · 명단 · Stream · 채널)는 모두 해시태그 `{b}`(b = gid mod [GameProperties.bucketCount])를
 * 달아 Cluster 에서 한 슬롯에 놓인다. `<date>` 는 KST 게임 날짜 `YYYY-MM-DD` 다.
 */
class RedisKeys(private val properties: GameProperties) {
    val bucketCount: Int get() = properties.bucketCount

    fun bucket(gid: GroupId): Int = properties.bucketOf(gid.value)

    /** 그룹 Hash — 한 그룹의 그날 진행 · 판정 상태. */
    fun group(gid: GroupId, date: LocalDate): String = "g:{${bucket(gid)}}:${gid.value}:$date"

    /** 그날 그룹원 명단 Hash(07:00 확정). */
    fun members(gid: GroupId, date: LocalDate): String = "${group(gid, date)}:members"

    /** 버킷의 그날 그룹 목록 Set — `worker: deadline` 이 돈다. */
    fun dayGroups(bucket: Int, date: LocalDate): String = "day:{${checkBucket(bucket)}}:$date"

    /** 버킷의 그날 재적재 표시 — `worker: member` 가 명단 확정을 끝내면 쓰고, 사라지면 Redis 를 잃은 것이다(LLD platform §4.2). */
    fun rehydrateMark(bucket: Int, date: LocalDate): String = "rk:{${checkBucket(bucket)}}:$date"

    /** 버킷 이벤트 Stream — `worker: persister` 가 소비한다. */
    fun events(bucket: Int): String = "ev:{${checkBucket(bucket)}}"

    /** 그룹 이벤트 Stream(그 그룹의 버킷). */
    fun events(gid: GroupId): String = events(bucket(gid))

    /** 그룹 채널(sharded Pub/Sub). */
    fun channel(gid: GroupId): String = "ch:{${bucket(gid)}}:${gid.value}"

    /** 실시간 티켓(30초 · 1회용). */
    fun ticket(ticket: String): String = "tk:{$ticket}"

    /** 로그인 — 살아 있는 refresh token 의 `jti`. */
    fun session(uid: UserId): String = "sess:{${uid.value}}"

    /** 결과 캐시 — 그룹의 가장 최근 확정 결과 · 랭킹. */
    fun result(gid: GroupId): String = "result:{${gid.value}}"

    /** 유저 문제은행(08:00 만료). */
    fun bank(uid: UserId, date: LocalDate): String = "bank:{${uid.value}}:$date"

    /** 로컬 데모 시계의 런타임 이동량(µs 십진 정수 — LLD platform §4.4). 스크립트 하나만 건드리는 키라 해시태그가 없다. */
    fun demoClock(): String = "demo:clock"

    /** 데모 시각 이동이 기다리는 경계의 일 완료 표시(Hash 필드 → 날짜 — LLD platform §4.4-5). */
    fun demoJobs(): String = "demo:jobs"

    /** [session] 키 전부를 고르는 SCAN 패턴 — 데모 시각 이동이 세션 TTL 을 줄일 때만 쓴다(LLD platform §4.4-3). */
    fun sessionPattern(): String = "sess:{*}"

    private fun checkBucket(bucket: Int): Int {
        require(bucket in 0 until bucketCount) { "버킷은 0 ~ ${bucketCount - 1} 이다: $bucket" }
        return bucket
    }
}
