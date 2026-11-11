package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.GroupStatus
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.result.MICROS_ISO_PATTERN
import com.delightroom.mission.core.domain.result.LatestResult
import com.delightroom.mission.core.domain.result.ResultCachePut
import com.delightroom.mission.core.domain.result.RankedMember
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.port.ResultCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [ResultCache] 의 Redis 구현 — `result:{<gid>}` 에 LLD deadline-result §2.4 JSON 을 둔다(`GET` · `SET NX EX` · Lua `result_cache_put`).
 * Kotlin 이 쓰고 Kotlin 이 읽는다(Lua `result_cache_put` 은 `date` · `rankingFinal` 만 본다).
 */
internal class RedisResultCache(
    private val redis: ReactiveStringRedisTemplate,
    private val scripts: RedisScripts,
    private val keys: RedisKeys,
    private val properties: GameProperties,
) : ResultCache {
    private val codec = ResultCacheCodec(properties)

    override suspend fun get(gid: GroupId): LatestResult? {
        val key = keys.result(gid)
        val raw = try {
            redis.opsForValue().get(key).awaitSingleOrNull()
        } catch (e: DataAccessException) {
            // 캐시는 정본이 아니다 — 읽기 오류는 캐시 없음으로 PostgreSQL 에 떨어진다(LLD §5)
            log.warn("result_cache.get_failed key={} reason={}", key, e.message)
            null
        } ?: return null
        return try {
            codec.decode(raw)
        } catch (e: IllegalArgumentException) {
            // 형식이 바뀐 값 — 버리고 PostgreSQL 로 다시 채운다(LLD §5). 지우기 실패는 무시한다(다음 조회가 또 버린다)
            log.warn("result_cache.unreadable key={} reason={}", key, e.message)
            try {
                redis.delete(key).awaitSingle()
            } catch (d: DataAccessException) {
                log.warn("result_cache.delete_failed key={} reason={}", key, d.message)
            }
            null
        }
    }

    override suspend fun putIfAbsent(gid: GroupId, result: LatestResult) {
        val key = keys.result(gid)
        try {
            redis.opsForValue().setIfAbsent(key, codec.encode(result), properties.resultCacheTtl).awaitSingle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("result_cache.put_failed key={} reason={}", key, e.message)
        }
    }

    override suspend fun putIfNotOlder(gid: GroupId, result: LatestResult): ResultCachePut {
        val key = keys.result(gid)
        return try {
            val reply = scripts.eval(
                "result_cache_put",
                listOf(key),
                listOf(
                    result.date.toString(),
                    if (result.rankingFinal) "1" else "0",
                    codec.encode(result),
                    properties.resultCacheTtl.seconds.toString(),
                ),
            )
            if (reply.singleOrNull() == 1L) ResultCachePut.WRITTEN else ResultCachePut.KEPT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("result_cache.put_failed key={} date={} reason={}", key, result.date, e.message)
            ResultCachePut.FAILED
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RedisResultCache::class.java)
    }
}

/** §2.4 JSON — 시각은 게임 시간대(`+09:00`) ISO-8601 마이크로초 6자리 문자열, 진행도 -1 은 `progress 0` · `started false`. */
internal class ResultCacheCodec(private val properties: GameProperties) {
    private val json: JsonMapper = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance

    fun encode(result: LatestResult): String {
        val root = nodes.objectNode()
        root.put("date", result.date.toString())
        root.put("groupStatus", result.groupStatus.name)
        root.put("decidedAt", result.decidedAt?.let(::time))
        root.put("rankingFinal", result.rankingFinal)
        val ranking = root.putArray("ranking")
        result.ranking.forEach { m ->
            ranking.addObject().apply {
                put("userId", m.loginId.value)
                put("rank", m.rank)
                put("progress", maxOf(m.progress, 0))
                put("status", m.status.name)
                put("started", m.progress >= 0)
                put("lastSolvedAt", m.lastSolvedAt?.let(::time))
                if (m.passNo == null) putNull("passedAtQuestionNo") else put("passedAtQuestionNo", m.passNo!!)
            }
        }
        return json.writeValueAsString(root)
    }

    /** 모양이 틀리면 [IllegalArgumentException] — 확정 결과(`SUCCEEDED` · `FAILED` · `decidedAt` 있음)만 읽은 것으로 친다(SPEC DR1). */
    fun decode(raw: String): LatestResult =
        try {
            val root = json.readTree(raw) as? ObjectNode ?: throw IllegalArgumentException("결과 캐시가 JSON 객체가 아님")
            val ranking = root.required("ranking")
            require(ranking.isArray) { "ranking 이 배열이 아님" }
            val groupStatus = GroupStatus.valueOf(root.text("groupStatus"))
            require(groupStatus == GroupStatus.SUCCEEDED || groupStatus == GroupStatus.FAILED) { "확정 결과가 아님: $groupStatus" }
            LatestResult(
                date = LocalDate.parse(root.text("date")),
                groupStatus = groupStatus,
                decidedAt = instant(root.text("decidedAt")),
                rankingFinal = root.required("rankingFinal").also { require(it.isBoolean) { "rankingFinal 이 boolean 이 아님" } }.booleanValue(),
                ranking = LatestResult.sorted(ranking.toList().map(::member)),
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            // JacksonException · DateTimeParseException 도 「읽지 못함」 하나로
            throw IllegalArgumentException("결과 캐시를 읽지 못함: ${e.message}", e)
        }

    private fun member(node: JsonNode): RankedMember {
        val started = node.required("started").also { require(it.isBoolean) { "started 가 boolean 이 아님" } }.booleanValue()
        val progress = node.int("progress")
        require(started || progress == 0) { "started false 인데 progress 가 0 이 아님: $node" }
        return RankedMember(
            loginId = LoginId(node.text("userId")),
            rank = node.int("rank"),
            progress = if (started) progress else -1,
            status = MemberStatus.valueOf(node.text("status")),
            lastSolvedAt = node.optText("lastSolvedAt")?.let(::instant),
            passNo = node.get("passedAtQuestionNo")?.takeUnless { it.isNull }?.let { it.also { n -> require(n.isIntegralNumber) }.intValue() },
        )
    }

    private fun time(at: Instant): String = FORMAT.format(at.atZone(properties.zone).toOffsetDateTime())

    private fun instant(raw: String): Instant = OffsetDateTime.parse(raw).toInstant()

    private fun JsonNode.required(name: String): JsonNode = requireNotNull(get(name)) { "$name 없음" }

    private fun JsonNode.text(name: String): String =
        required(name).also { require(it.isString) { "$name 이 문자열이 아님" } }.stringValue()

    private fun JsonNode.optText(name: String): String? = get(name)?.takeUnless { it.isNull }?.let { text(name) }

    private fun JsonNode.int(name: String): Int =
        required(name).also { require(it.isIntegralNumber) { "$name 이 정수가 아님" } }.intValue()

    companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(MICROS_ISO_PATTERN)
    }
}
