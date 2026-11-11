package com.delightroom.mission.app.api.signal

import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.app.api.auth.JwtTokenIssuer
import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.LocalTime

/**
 * ORACLE solve-signal O1 · O2 · O4 · O5 · O6 · I1(api 경로) — 지금 = 07:40(창 안).
 * 판정 자체(Lua)는 `infra.redis.signal.*` 가 본다. 여기서는 HTTP 계약 — 상태 코드 · 본문 · 검증 · 인증 · 참가.
 */
class SolveApiTest : SignalApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun at0740(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(7, 40))
    }

    private fun twoMembers() = group(Member(ME, "me_1", p = 0, cur = Q1), Member(OTHER, "other_2"))

    @Test
    fun `O1 풀이 정상 반영 — 200 progress 1 · 지금 문제 비움 · version +1 · Stream solve_applied`() {
        twoMembers()

        val actual = body(post(SOLVES, access(ME), solveBody()), 200)

        assertThat(actual).isEqualTo(mapOf("progress" to 1, "missionStatus" to "IN_PROGRESS", "groupStatus" to "IN_PROGRESS"))
        val hash = hash()
        assertThat(hash["$ME:p"]).isEqualTo("1")
        assertThat(hash).doesNotContainKey("$ME:cur")
        assertThat(hash["$ME:ls"]).isNotNull()
        assertThat(version()).isEqualTo(1)
        assertThat(events()).containsExactly("solve.applied")
    }

    @Test
    fun `O2 풀이 재시도는 같은 200 본문 · version +0 · Stream +0`() {
        twoMembers()
        val first = post(SOLVES, access(ME), solveBody()).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody

        val second = post(SOLVES, access(ME), solveBody()).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody

        // 07:40 · 결과 없음이라 groupStatus 도 그대로(SS2 — 지금 값) — 본문 전체가 같다
        assertThat(second).isEqualTo(first)
        assertThat(version()).isEqualTo(1)
        assertThat(events()).containsExactly("solve.applied")
    }

    @Test
    fun `O4 본문 검증 8건 — 모두 400 INVALID_REQUEST · Redis 호출 0회(version +0 · Stream +0)`() {
        twoMembers()
        val good = solveBody()
        val cases = listOf(
            SOLVES to good + ("questionNo" to 0),
            SOLVES to good + ("questionNo" to 4),
            SOLVES to good + ("questionId" to "a".repeat(63)),
            SOLVES to good + ("questionId" to "A".repeat(64)),
            SOLVES to good + ("clickedAt" to "2026-11-12T07:00:00"),
            SOLVES to good - "successTime",
            PASSES to passBody() - "questionNo",
            PASSES to passBody() + ("questionId" to "a".repeat(65)),
        )

        for ((path, request) in cases) {
            assertThat(code(post(path, access(ME), request), 400)).describedAs("$path $request").isEqualTo("INVALID_REQUEST")
        }
        assertThat(version()).isZero()
        assertThat(events()).isEmpty()
        assertThat(hash()["$ME:p"]).isEqualTo("0")
    }

    @Test
    fun `O4 (바탕) 본문 없음 · JSON 아님 · 숫자 시각 · 타입 위반도 400 INVALID_REQUEST`() {
        twoMembers()
        val cases = listOf(
            SOLVES to null,
            SOLVES to "not json",
            SOLVES to solveBody() + ("clickedAt" to 1_763_000_000),
            SOLVES to solveBody() + ("successTime" to "어제"),
            SOLVES to solveBody() + ("questionNo" to "one"),
            PASSES to null,
            PASSES to passBody() + ("questionId" to null),
        )
        for ((path, request) in cases) {
            val spec = if (request is String) {
                client.post().uri(path).cookie(AuthCookies.ACCESS, access(ME))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange()
            } else {
                post(path, access(ME), request)
            }
            assertThat(code(spec, 400)).describedAs("$path $request").isEqualTo("INVALID_REQUEST")
        }
        assertThat(version()).isZero()
    }

    @Test
    fun `O5 인증 9건 — 세 엔드포인트 × 쿠키 없음 · 만료 · 위조 모두 401 UNAUTHORIZED`() {
        twoMembers()
        val expired = access(ME, issuedAt = gameProperties.now(clock).minus(Duration.ofHours(2)))
        val otherKey = JwtTokenIssuer(AuthProperties(jwtSecret = "another-secret-that-is-32-bytes-long!!"))
        val forged = otherKey.issue(Account(UserId(ME), LoginId("me_1"), "", gid, GroupName("신호")), "jti", gameProperties.now(clock)).access

        for (path in listOf(SOLVES to solveBody(), PASSES to passBody(), FAILURES to null)) {
            for (token in listOf(null, expired, forged)) {
                assertThat(code(post(path.first, token, path.second), 401)).describedAs("${path.first} $token").isEqualTo("UNAUTHORIZED")
            }
        }
        assertThat(version()).isZero()
    }

    @Test
    fun `O6 명단에 없는 유저 — 세 엔드포인트 모두 403 NOT_PARTICIPANT`() {
        twoMembers()
        val outsider = access(999L)

        assertThat(code(post(SOLVES, outsider, solveBody()), 403)).isEqualTo("NOT_PARTICIPANT")
        assertThat(code(post(PASSES, outsider, passBody()), 403)).isEqualTo("NOT_PARTICIPANT")
        assertThat(code(post(FAILURES, outsider), 403)).isEqualTo("NOT_PARTICIPANT")
        assertThat(version()).isZero()
    }

    @Test
    fun `I1 같은 풀이 신호 100건 동시(api 경유) — 모두 200 같은 본문 · 1회 반영`(): Unit = runBlocking {
        twoMembers()
        val token = access(ME)

        val bodies = (1..100).map {
            async(Dispatchers.IO) {
                post(SOLVES, token, solveBody()).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody
            }
        }.awaitAll()

        assertThat(bodies.toSet()).hasSize(1)
        assertThat(json.readValue(bodies.first(), Map::class.java)["progress"]).isEqualTo(1)
        assertThat(hash()["$ME:p"]).isEqualTo("1")
        assertThat(version()).isEqualTo(1)
        assertThat(events()).containsExactly("solve.applied")
    }
}
