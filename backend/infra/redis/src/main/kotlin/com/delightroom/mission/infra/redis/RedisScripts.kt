package com.delightroom.mission.infra.redis

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.data.redis.connection.ReturnType
import org.springframework.data.redis.core.ReactiveRedisCallback
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lua 로더 — 기능 Lua 는 모두 이 하나로 돈다(LLD platform §1).
 *
 * `<root>/lib/` 의 `.lua` 를 모두 앞에 붙인 `<root>/<name>.lua` 를 처음 쓸 때 `SCRIPT LOAD` 하고 `EVALSHA` 로 부른다.
 * `NOSCRIPT`(Redis 재시작 · `SCRIPT FLUSH` · 장애 조치)면 다시 올리고 **1회만** 재시도한다.
 *
 * lib 모듈 규약: 파일 이름(확장자 뺀 것)이 모듈 이름이다. 맨 앞에 `local <모듈들>` 을 선언해 두므로
 * lib 파일은 `<이름> = {}` 로 채우고, lib 끼리는 파일 순서와 상관없이 서로 부른다.
 *
 * lib 는 로더를 만들 때(빈 생성 = 기동) 읽고 검사한다 — 규약 위반은 첫 요청이 아니라 기동 실패로 드러난다.
 *
 * 반환은 목록이다 — 문자열 칸은 [String], 정수 칸은 [Long], 안쪽 배열은 [List]. 스칼라 반환은 한 칸 목록이다.
 * Lua 표 안의 `nil` 은 배열을 그 자리에서 잘라 조용히 짧은 목록이 되고, `false` 는 RESP nil 이라 오류로 끝낸다 —
 * 스크립트는 빈 칸을 `''` 로 돌려주고, 쓰는 쪽은 칸 수를 확인한다.
 */
class RedisScripts(
    private val redis: ReactiveStringRedisTemplate,
    private val root: String = "lua",
) {
    private class Script(val name: String, val source: String, val sha: String) {
        @Volatile var loaded: Boolean = false
    }

    private val scripts = ConcurrentHashMap<String, Script>()
    private val libSource: String = readLibs()

    /** [name] 스크립트를 [keys] · [args] 로 돌린다. */
    suspend fun eval(name: String, keys: List<String>, args: List<String>): List<Any> {
        val script = scripts.computeIfAbsent(name, ::compile)
        if (!script.loaded) load(script)
        return try {
            evalSha(script, keys, args)
        } catch (e: Exception) {
            if (!e.isNoScript()) throw e
            script.loaded = false
            load(script)
            evalSha(script, keys, args)
        }
    }

    /** 이어 붙인 본문의 SHA-1 — `SCRIPT LOAD` 가 돌려주는 값과 같다. */
    fun sha(name: String): String = scripts.computeIfAbsent(name, ::compile).sha

    private suspend fun load(script: Script) {
        val sha = redis.execute(ReactiveRedisCallback<String> { conn ->
            conn.scriptingCommands().scriptLoad(ByteBuffer.wrap(script.source.toByteArray(UTF_8)))
        }).awaitSingle()
        check(sha == script.sha) { "SCRIPT LOAD 가 돌려준 SHA($sha)가 계산한 값(${script.sha})과 다르다: ${script.name}" }
        script.loaded = true
    }

    private suspend fun evalSha(script: Script, keys: List<String>, args: List<String>): List<Any> {
        val keysAndArgs = (keys + args).map { ByteBuffer.wrap(it.toByteArray(UTF_8)) }.toTypedArray()
        val reply = redis.execute(ReactiveRedisCallback<Any> { conn ->
            conn.scriptingCommands().evalSha<Any>(script.sha, ReturnType.MULTI, keys.size, *keysAndArgs)
        }).collectList().awaitSingle()
        // Lettuce 는 배열 응답 전체를 원소 하나(List)로 내보낸다 — 그 한 겹을 벗긴다. 스칼라 응답은 그대로 한 칸이다.
        val whole = reply.singleOrNull()
        return if (whole is List<*>) whole.map(::decode) else reply.map(::decode)
    }

    private fun compile(name: String): Script {
        require(NAME.matches(name)) { "스크립트 이름은 영문 · 숫자 · _ 만 쓴다: $name" }
        val body = ClassPathResource("$root/$name.lua", javaClass.classLoader)
        require(body.exists()) { "스크립트가 없다: classpath:$root/$name.lua" }
        val source = libSource + body.inputStream.use { String(it.readAllBytes(), UTF_8) }
        return Script(name, source, sha1(source))
    }

    private fun readLibs(): String {
        val libs = PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResources("classpath*:$root/lib/*.lua")
            .map { requireNotNull(it.filename) to it }
            .sortedBy { it.first }
        if (libs.isEmpty()) return ""
        val modules = libs.map { it.first.removeSuffix(".lua") }
        modules.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.let { dup ->
            check(dup.isEmpty()) { "lib 모듈 이름이 겹친다: $dup" }
        }
        modules.forEach { check(IDENTIFIER.matches(it)) { "lib 모듈 이름은 Lua 식별자여야 한다: $it.lua" } }
        return buildString {
            append("local ").append(modules.joinToString(", ")).append('\n')
            libs.forEach { (_, resource) ->
                append(resource.inputStream.use { String(it.readAllBytes(), UTF_8) }).append('\n')
            }
        }
    }

    private fun decode(value: Any?): Any = when (value) {
        is ByteBuffer -> UTF_8.decode(value).toString()
        is ByteArray -> String(value, UTF_8)
        is List<*> -> value.map(::decode)
        null -> error("Lua 반환 배열에 nil 이 있다 — 빈 문자열을 돌려준다")
        else -> value
    }

    private fun Throwable.isNoScript(): Boolean =
        generateSequence(this) { it.cause }.any { it.message?.contains("NOSCRIPT") == true }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]+")
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun sha1(source: String): String =
            MessageDigest.getInstance("SHA-1").digest(source.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
