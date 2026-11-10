package com.delightroom.mission.core.testfixture

import org.yaml.snakeyaml.Yaml
import java.io.File

/** docs/api/openapi.yaml 의 오퍼레이션 — 라우트 스모크 테스트가 이 목록을 그대로 두드린다. */
data class OpenApiOperation(
    val method: String,
    val path: String,
    val requestExample: Any?,
    val queryExamples: Map<String, String>,
)

object OpenApiContract {
    private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")

    /** [app] 은 오퍼레이션의 `x-app` 값(api · realtime). 파일 경로는 시스템 속성 `openapi.path`. */
    @Suppress("UNCHECKED_CAST")
    fun operations(app: String): List<OpenApiOperation> {
        val path = requireNotNull(System.getProperty("openapi.path")) { "openapi.path 시스템 속성이 없다" }
        val root = File(path).inputStream().use { Yaml().load<Map<String, Any>>(it) }
        val paths = root["paths"] as Map<String, Map<String, Any>>
        return paths.flatMap { (route, item) ->
            item.filterKeys { it in HTTP_METHODS }.map { (method, raw) ->
                val op = raw as Map<String, Any>
                val example = ((op["requestBody"] as Map<String, Any>?)
                    ?.get("content") as Map<String, Map<String, Any>>?)
                    ?.get("application/json")?.get("example")
                val queries = (op["parameters"] as List<Map<String, Any>>?).orEmpty()
                    .filter { it["in"] == "query" }
                    .associate { it["name"] as String to it["example"].toString() }
                Triple(op["x-app"], OpenApiOperation(method.uppercase(), route, example, queries), Unit)
            }
        }.filter { it.first == app }.map { it.second }
    }
}
