package com.delightroom.mission.infra.redis

import com.delightroom.mission.core.domain.board.GroupDaySnapshot
import com.delightroom.mission.core.domain.board.microsToInstant
import com.delightroom.mission.core.domain.event.GroupChannelMessage
import com.delightroom.mission.core.domain.event.GroupChannelType
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 그룹 채널 메시지 JSON(LLD realtime-board §2.3) ↔ [GroupChannelMessage].
 * 발행은 신호 Lua 의 `cjson` 이 하므로 [encode] 는 테스트가 Lua 와 같은 모양을 `SPUBLISH` 할 때 쓴다.
 *
 * 시각 · id 는 문자열이다(cjson 은 숫자를 14자리로 줄인다). 모르는 key 는 무시하고, `version` 이 없으면 `hash.version` 을 쓴다.
 */
class GroupChannelCodec {
    private val json: JsonMapper = JsonMapper.builder().build()

    /** 모양이 틀리면 [IllegalArgumentException] — JSON 파싱 실패 · `hash` 없음 · 모르는 `type` · 그룹 Hash 파싱 실패. */
    fun decode(raw: String): GroupChannelMessage {
        val root = try {
            json.readTree(raw)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("채널 메시지 JSON 을 읽지 못함", e)
        }
        require(root.isObject) { "채널 메시지가 JSON 객체가 아님" }
        val hashNode = root.get("hash")
        require(hashNode != null && hashNode.isObject) { "채널 메시지에 hash 객체 없음" }
        val hash = LinkedHashMap<String, String>()
        for ((field, value) in hashNode.properties()) {
            require(value.isString || value.isNumber) { "hash.$field 가 문자열 · 숫자가 아님" }
            hash[field] = if (value.isString) value.stringValue() else value.asString()
        }
        val type = text(root, "type")!!.let { requireNotNull(GroupChannelType.ofWire(it)) { "모르는 type: $it" } }
        val date = try {
            LocalDate.parse(text(root, "date")!!)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("date 틀림", e)
        }
        val snapshot = GroupDaySnapshot.parse(date, hash)
        return GroupChannelMessage(
            type = type,
            groupId = GroupId(long(root, "gid")!!),
            date = date,
            streamId = text(root, "streamId", nullable = true),
            changedUserId = long(root, "uid", nullable = true)?.let(::UserId),
            version = long(root, "version", nullable = true) ?: snapshot.version,
            at = microsToInstant(long(root, "at")!!),
            snapshot = snapshot,
        )
    }

    /** Lua 가 내는 것과 같은 JSON 한 줄. [atMicros] 는 에포크 µs. */
    fun encode(
        type: GroupChannelType,
        groupId: GroupId,
        date: LocalDate,
        streamId: String?,
        changedUserId: UserId?,
        version: Long,
        atMicros: Long,
        hash: Map<String, String>,
    ): String {
        val root = json.createObjectNode()
        root.put("type", type.wire)
        if (streamId == null) root.putNull("streamId") else root.put("streamId", streamId)
        root.put("version", version)
        root.put("date", date.toString())
        root.put("gid", groupId.value.toString())
        if (changedUserId == null) root.putNull("uid") else root.put("uid", changedUserId.value.toString())
        root.put("at", atMicros.toString())
        val hashNode = root.putObject("hash")
        hash.forEach { (field, value) -> hashNode.put(field, value) }
        return json.writeValueAsString(root)
    }

    private fun text(root: JsonNode, name: String, nullable: Boolean = false): String? {
        val node = root.get(name)
        if (node == null || node.isNull) {
            require(nullable) { "$name 없음" }
            return null
        }
        require(node.isString) { "$name 이 문자열이 아님" }
        return node.stringValue()
    }

    /** 정수 칸 — 문자열(`"42"`)과 정수 둘 다 받는다. */
    private fun long(root: JsonNode, name: String, nullable: Boolean = false): Long? {
        val node = root.get(name)
        if (node == null || node.isNull) {
            require(nullable) { "$name 없음" }
            return null
        }
        if (node.isIntegralNumber && node.canConvertToLong()) return node.longValue()
        require(node.isString) { "$name 이 정수가 아님" }
        return requireNotNull(node.stringValue().toLongOrNull()) { "$name 이 정수가 아님: ${node.stringValue()}" }
    }

    companion object {
        /** [Instant] → 에포크 µs(µs 아래는 버린다). */
        fun micros(instant: Instant): Long =
            Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), (instant.nano / 1_000).toLong())
    }
}
