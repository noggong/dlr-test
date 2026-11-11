package com.delightroom.mission.core.testfixture

import org.yaml.snakeyaml.Yaml
import java.io.File

/** docs/api/asyncapi.yaml 의 메시지 · 스키마 — realtime 계약 테스트(ORACLE realtime-board O30)가 구현과 대조한다. */
object AsyncApiContract {
    /** 파일 경로는 시스템 속성 `asyncapi.path`. */
    @Suppress("UNCHECKED_CAST")
    private val root: Map<String, Any> by lazy {
        val path = requireNotNull(System.getProperty("asyncapi.path")) { "asyncapi.path 시스템 속성이 없다" }
        File(path).inputStream().use { Yaml().load<Map<String, Any>>(it) }
    }

    /** 원문 — 지운 필드 이름(`cheatFailed` 등)이 어디에도 남지 않았는지 볼 때 쓴다. */
    fun text(): String = File(requireNotNull(System.getProperty("asyncapi.path"))).readText()

    @Suppress("UNCHECKED_CAST")
    private fun components(kind: String): Map<String, Map<String, Any>> =
        (root["components"] as Map<String, Any>)[kind] as Map<String, Map<String, Any>>

    /** components.messages 의 `name`(= SSE `event:` 이름). */
    fun messageNames(): List<String> = components("messages").values.map { it["name"] as String }

    /** 그 이름의 메시지 `payload` 가 가리키는 스키마의 필드. */
    fun payloadFields(messageName: String): Set<String> {
        val message = components("messages").values.single { it["name"] == messageName }
        @Suppress("UNCHECKED_CAST")
        val ref = (message["payload"] as Map<String, String>).getValue("\$ref")
        return schemaFields(ref.substringAfterLast('/'))
    }

    /** 스키마의 필드 — `properties` 와 `required` 가 다르면 실패한다(모든 필드를 언제나 싣는다). */
    fun schemaFields(schema: String): Set<String> = ContractSchemas.fields(components("schemas"), schema)
}

internal object ContractSchemas {
    @Suppress("UNCHECKED_CAST")
    fun fields(schemas: Map<String, Map<String, Any>>, schema: String): Set<String> {
        val body = requireNotNull(schemas[schema]) { "스키마 $schema 가 없다" }
        val properties = (body["properties"] as Map<String, Any>).keys
        val required = (body["required"] as List<String>).toSet()
        check(properties == required) { "$schema 의 properties $properties 와 required $required 가 다르다" }
        return properties
    }
}
