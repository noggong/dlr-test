package com.delightroom.mission.app.api.auth

import com.delightroom.mission.app.api.PostgresTestContainer
import com.delightroom.mission.core.testfixture.MutableClockConfiguration
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * auth API 통합 테스트의 바탕 — 앱 컨텍스트 + Testcontainers PostgreSQL(RouteSmokeTest 와 같은 컨텍스트를 나눠 쓴다).
 * 테스트끼리 DB 를 비우지 않으므로 아이디 · 그룹명은 [uniqueLoginId] · [uniqueGroupName] 으로 매번 새로 만든다.
 */
@SpringBootTest
@Import(MutableClockConfiguration::class)
@ContextConfiguration(initializers = [PostgresTestContainer.Initializer::class])
abstract class AuthApiTestSupport {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    protected lateinit var db: DatabaseClient

    protected val client: WebTestClient by lazy { WebTestClient.bindToApplicationContext(context).build() }

    protected fun signUp(
        loginId: String,
        groupName: String,
        mode: String = "NEW",
        password: String = PASSWORD,
    ): EntityExchangeResult<Map<String, Any>> =
        postSignUp(mapOf("loginId" to loginId, "password" to password, "group" to mapOf("mode" to mode, "name" to groupName)))

    protected fun postSignUp(body: Any): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(SIGNUP).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange()
            .expectBody(MAP_TYPE)
            .returnResult()

    /** 정원까지 채운 그룹 — 첫 사람이 NEW, 나머지가 EXISTING. 만든 아이디 목록을 돌려준다. */
    protected fun groupOf(groupName: String, size: Int): List<String> =
        List(size) { i ->
            uniqueLoginId().also { id ->
                val result = signUp(id, groupName, if (i == 0) "NEW" else "EXISTING")
                check(result.status.value() == 201) { "준비 가입 실패: ${result.responseBody}" }
            }
        }

    protected fun memberCount(groupName: String): Long? =
        queryLong("SELECT member_count FROM groups WHERE name_key = lower(:name)", "name" to groupName)

    protected fun userCountOf(groupName: String): Long =
        queryLong(
            "SELECT count(*) FROM users u JOIN groups g ON g.id = u.group_id WHERE g.name_key = lower(:name)",
            "name" to groupName,
        )!!

    protected fun count(table: String): Long = queryLong("SELECT count(*) FROM $table")!!

    protected fun queryLong(sql: String, vararg binds: Pair<String, Any>): Long? =
        runBlocking {
            binds.fold(db.sql(sql)) { spec, (k, v) -> spec.bind(k, v) }
                .map { row -> (row.get(0) as Number).toLong() }
                .all().collectList().awaitSingle().firstOrNull()
        }

    /** ORACLE I5 — `member_count` 가 소속 유저 수와 다르거나 10 을 넘는 그룹 수. */
    protected fun brokenGroupCount(): Long =
        queryLong(
            "SELECT count(*) FROM groups g WHERE member_count <> " +
                "(SELECT count(*) FROM users u WHERE u.group_id = g.id) OR member_count > 10",
        )!!

    companion object {
        const val SIGNUP = "/api/v1/auth/signup"
        const val PASSWORD = "correct-horse-9"

        private val seq = AtomicInteger()
        private val run = (System.nanoTime() % 46_656).toString(36).padStart(3, '0')

        val MAP_TYPE = object : org.springframework.core.ParameterizedTypeReference<Map<String, Any>>() {}

        /** `^[a-z0-9_]{4,20}$` 안의 새 아이디. */
        fun uniqueLoginId(): String = "u${run}_${seq.incrementAndGet()}"

        /** 2~20자 그룹명 — [base] 는 12자 이하로. */
        fun uniqueGroupName(base: String = "Team"): String = "$base-$run${seq.incrementAndGet()}"
    }
}
