package com.delightroom.mission.app.api.auth

import com.delightroom.mission.app.api.dto.GroupChoiceRequest
import com.delightroom.mission.app.api.dto.GroupMode
import com.delightroom.mission.app.api.dto.LoginRequest
import com.delightroom.mission.app.api.dto.SignUpRequest
import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.io.File
import java.net.URI
import java.time.Instant

/** 로그인 API — ORACLE auth-signup O4(로그인 쪽) · O14 · O15 · O17 ~ O20 · O30 · I8. 계약은 SPEC §2.2 · AS9 · AS10 · AS16. */
@ExtendWith(OutputCaptureExtension::class)
class LoginApiTest : AuthApiTestSupport() {
    private fun EntityExchangeResult<Map<String, Any>>.assertError(status: Int, code: String) {
        assertThat(this.status.value()).describedAs("본문 %s", responseBody).isEqualTo(status)
        assertThat(responseBody!!["code"]).isEqualTo(code)
        assertThat(responseBody!!["message"]).isInstanceOf(String::class.java)
        assertThat(responseHeaders.getCacheControl()).isEqualTo("no-store")
        assertThat(responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
    }

    private fun rawPost(contentType: MediaType, body: String): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(LOGIN).contentType(contentType).bodyValue(body)
            .exchange().expectBody(MAP_TYPE).returnResult()

    // ---- O4 본문 형식 오류(로그인 쪽) ----

    @Test
    fun `O4 JSON 이 아닌 본문은 400 INVALID_REQUEST`() {
        rawPost(MediaType.APPLICATION_JSON, "{not json").assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `O4 loginId 가 빠지면 400 INVALID_REQUEST`() {
        rawPost(MediaType.APPLICATION_JSON, """{"password":"$PASSWORD"}""").assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `O4 Content-Type 이 text plain 이면 400 INVALID_REQUEST`() {
        rawPost(MediaType.TEXT_PLAIN, """{"loginId":"alice_01","password":"$PASSWORD"}""").assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `AS17 퍼센트 인코딩 · 매트릭스 경로로 들어온 로그인 응답에도 no-store`() {
        for (path in listOf("/api/v1/%61uth/login", "/api/v1/auth;x=1/login")) {
            val result = client.post().uri(URI.create(path)).contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange().expectBody(MAP_TYPE).returnResult()
            // 라우팅이 디코딩해 로그인 컨트롤러에 닿는다 — 같은 경로를 캐시 필터도 auth 로 봐야 한다
            result.assertError(400, "INVALID_REQUEST")
        }
    }

    // ---- O14 · O15 정상 ----

    @Test
    fun `O14 O30 로그인 200 의 본문 키와 만료 시각 · 토큰 문자열은 본문 헤더에 없다`() {
        val id = newAccount()
        setClock("2026-11-12T06:40:00+09:00")

        val result = postLogin(id)

        assertThat(result.status.value()).describedAs("%s", result.responseBody).isEqualTo(200)
        val body = result.responseBody!!
        assertThat(body.keys).containsExactlyInAnyOrder("userId", "groupName", "accessExpiresAt", "refreshExpiresAt")
        assertThat(body["userId"]).isEqualTo(id)
        assertThat(body["accessExpiresAt"]).isEqualTo("2026-11-12T07:40:00+09:00")
        assertThat(body["refreshExpiresAt"]).isEqualTo("2026-11-12T08:40:00+09:00")
        assertThat(result.responseHeaders.getCacheControl()).isEqualTo("no-store")

        // AS16 — 토큰은 Set-Cookie 에만
        val access = cookieValue(result, AuthCookies.ACCESS)
        val refresh = cookieValue(result, AuthCookies.REFRESH)
        assertThat(access).isNotEmpty()
        assertThat(refresh).isNotEmpty()
        assertThat(body.values.map { it.toString() }).noneMatch { it.contains(access) || it.contains(refresh) }
        val otherHeaders = result.responseHeaders.headerNames()
            .filterNot { it.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
            .flatMap { result.responseHeaders[it].orEmpty() }
        assertThat(otherHeaders).noneMatch { it.contains(access) || it.contains(refresh) }
    }

    @Test
    fun `O15 로그인 쿠키 속성이 SPEC 쿠키 표와 같다`() {
        val id = newAccount()
        setClock("2026-11-12T06:40:00+09:00")

        val result = postLogin(id)

        val access = setCookie(result, AuthCookies.ACCESS)!!
        val refresh = setCookie(result, AuthCookies.REFRESH)!!
        assertThat(attributes(access)).containsExactly(
            "Path=/api", "Max-Age=3600", "Expires=Wed, 11 Nov 2026 22:40:00 GMT", "Secure", "HttpOnly", "SameSite=Lax",
        )
        assertThat(attributes(refresh)).containsExactly(
            "Path=/api/v1/auth", "Max-Age=7200", "Expires=Wed, 11 Nov 2026 23:40:00 GMT", "Secure", "HttpOnly", "SameSite=Lax",
        )
        assertThat(access + refresh).doesNotContainIgnoringCase("Domain=")
        assertThat(result.responseHeaders[HttpHeaders.SET_COOKIE]).hasSize(2)
    }

    @Test
    fun `O15 cookieSecure 가 false 면 Secure 만 빠진다`() {
        val properties = AuthProperties(jwtSecret = "mission-local-jwt-secret-not-for-production", cookieSecure = false)
        val tokens = JwtTokenIssuer(properties).issue(
            Account(UserId(1), LoginId("alice_01"), "", GroupId(1), GroupName("Team-A")),
            "jti",
            Instant.parse("2026-11-11T21:40:00Z"),
        )

        val cookies = AuthCookies(properties).issue(tokens)

        assertThat(attributes(cookies[0])).containsExactly(
            "Path=/api", "Max-Age=3600", "Expires=Wed, 11 Nov 2026 22:40:00 GMT", "HttpOnly", "SameSite=Lax",
        )
        assertThat(attributes(cookies[1])).containsExactly(
            "Path=/api/v1/auth", "Max-Age=7200", "Expires=Wed, 11 Nov 2026 23:40:00 GMT", "HttpOnly", "SameSite=Lax",
        )
    }

    // ---- O17 ~ O20 ----

    @Test
    fun `O17 O30 없는 아이디 · 틀린 비밀번호 · 대문자 아이디는 같은 401 INVALID_CREDENTIALS`() {
        val id = newAccount() // 소문자 아이디 — 대문자로 바꾼 것은 없는 아이디다
        val sessionsBefore = sessionKeyCount()

        val results = listOf(
            postLogin(uniqueLoginId()),
            postLogin(id, "wrong-password-1"),
            postLogin(id.uppercase()),
            postLogin(id, "short"), // 형식 밖 비밀번호도 400 이 아니라 같은 401(LLD §3.3)
        )

        results.forEach { it.assertError(401, "INVALID_CREDENTIALS") }
        assertThat(results.map { it.responseBody!!["message"] }.distinct()).hasSize(1)
        assertThat(sessionKeyCount()).isEqualTo(sessionsBefore)
        assertThat(sessionValue(id)).isNull()
    }

    @Test
    fun `O18 로그인 중에 다시 로그인하면 409 ALREADY_LOGGED_IN 이고 세션은 첫 로그인 그대로`() {
        val (id, first) = loggedIn()
        val firstJti = jtiOf(cookieValue(first, AuthCookies.REFRESH))

        postLogin(id).assertError(409, "ALREADY_LOGGED_IN")

        assertThat(sessionValue(id)).isEqualTo(firstJti)
    }

    @Test
    fun `O19 로그인 중인 계정에 틀린 비밀번호면 409 가 아니라 401 INVALID_CREDENTIALS`() {
        val (id, _) = loggedIn()

        postLogin(id, "wrong-password-1").assertError(401, "INVALID_CREDENTIALS")
    }

    @Test
    fun `O20 로그인 직후 세션 키 TTL 은 7190~7200 이고 값은 refresh jti`() {
        val (id, result) = loggedIn()

        assertThat(sessionValue(id)).isEqualTo(jtiOf(cookieValue(result, AuthCookies.REFRESH)))
        assertThat(sessionTtlSeconds(id)).isBetween(7190, 7200)
    }

    // ---- I8 비밀 값 ----

    @Test
    fun `I8 평문 비밀번호는 로그에 나오지 않는다`(output: CapturedOutput) {
        val secret = "plain-${uniqueLoginId()}-pw"
        val id = uniqueLoginId()
        signUp(id, uniqueGroupName(), password = secret)

        postLogin(id, secret)
        postLogin(id, secret) // 409
        postLogin(id, "$secret-x") // 401

        assertThat(output.all).doesNotContain(secret)
        // 디버그 로그가 요청 DTO 를 찍어도 평문이 실리지 않는다
        assertThat(LoginRequest(id, secret).toString()).doesNotContain(secret)
        assertThat(SignUpRequest(id, secret, GroupChoiceRequest(GroupMode.NEW, "Team")).toString()).doesNotContain(secret)
    }

    @Test
    fun `I8 레포 설정 · 코드에 AUTH_JWT_SECRET 값이 없다(로컬 기본값 문구 말고)`() {
        val repo = File(System.getProperty("openapi.path")).parentFile.parentFile.parentFile
        val pattern = Regex("""AUTH_JWT_SECRET *[:=] *[^$ ]""")
        val hits = listOf("iac", "backend").flatMap { dir ->
            File(repo, dir).walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", "node_modules") }
                .filter { it.isFile && it.extension in setOf("yaml", "yml", "kts", "kt") }
                .flatMap { file -> file.readLines().filter { pattern.containsMatchIn(it) }.map { "${file.path}: $it" } }
                .toList()
        }

        assertThat(hits).allMatch { it.contains("mission-local-jwt-secret-not-for-production") }
    }

    private fun attributes(setCookie: String): List<String> = setCookie.split("; ").drop(1)
}
