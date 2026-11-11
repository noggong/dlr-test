package com.delightroom.mission.app.api.auth

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** 가입 API — ORACLE auth-signup O4(가입 쪽) · O5 ~ O13 · I5. 계약은 SPEC auth-signup §2.1 · AS5 ~ AS8 · AS15 · AS17. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SignUpApiTest : AuthApiTestSupport() {
    private fun EntityExchangeResult<Map<String, Any>>.assertError(status: Int, code: String) {
        assertThat(this.status.value()).describedAs("본문 %s", responseBody).isEqualTo(status)
        assertThat(responseBody!!["code"]).isEqualTo(code)
        assertThat(responseBody!!["message"]).isInstanceOf(String::class.java)
        assertThat(responseHeaders.getCacheControl()).isEqualTo("no-store")
        assertThat(responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
    }

    private fun rawPost(contentType: MediaType, body: String): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(SIGNUP).contentType(contentType).bodyValue(body)
            .exchange().expectBody(MAP_TYPE).returnResult()

    // ---- O4 본문 형식 오류(가입 쪽) ----

    @Test
    fun `O4 JSON 이 아닌 본문은 400 INVALID_REQUEST`() {
        rawPost(MediaType.APPLICATION_JSON, "{not json").assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `O4 loginId 가 빠지면 400 INVALID_REQUEST`() {
        postSignUp(mapOf("password" to PASSWORD, "group" to mapOf("mode" to "NEW", "name" to uniqueGroupName())))
            .assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `O4 group mode 가 enum 밖(JOIN)이면 400 INVALID_REQUEST`() {
        postSignUp(mapOf("loginId" to uniqueLoginId(), "password" to PASSWORD, "group" to mapOf("mode" to "JOIN", "name" to uniqueGroupName())))
            .assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `O4 Content-Type 이 text plain 이면 400 INVALID_REQUEST`() {
        val body = """{"loginId":"${uniqueLoginId()}","password":"$PASSWORD","group":{"mode":"NEW","name":"${uniqueGroupName()}"}}"""
        rawPost(MediaType.TEXT_PLAIN, body).assertError(400, "INVALID_REQUEST")
    }

    // ---- O1 · O2 · O3 의 API 쪽 — 입력 규칙 위반은 400, 여러 필드면 한 번에 ----

    @Test
    fun `O1 O2 O3 입력 규칙 위반은 400 INVALID_REQUEST 이고 어긴 필드를 모두 적는다`() {
        val groupsBefore = count("groups")
        val result = signUp("Alice", "팀!", password = "short")
        result.assertError(400, "INVALID_REQUEST")
        assertThat(result.responseBody!!["message"] as String)
            .contains("loginId").contains("password").contains("group.name")
            .doesNotContain("short")
        assertThat(count("groups")).isEqualTo(groupsBefore)
    }

    // ---- O5 · O6 정상 가입 ----

    @Test
    fun `O5 NEW 가입은 201 이고 본문 4필드 · 앞뒤 공백 지운 그룹명 · member_count 1 · 쿠키 없음`() {
        val name = uniqueGroupName("Team-A 아침")
        val loginId = uniqueLoginId()
        val result = signUp(loginId, "  $name ")

        assertThat(result.status.value()).describedAs("본문 %s", result.responseBody).isEqualTo(201)
        assertThat(result.responseBody!!.keys).containsExactlyInAnyOrder("userId", "groupName", "joinedAt", "firstGameDate")
        assertThat(result.responseBody!!["userId"]).isEqualTo(loginId)
        assertThat(result.responseBody!!["groupName"]).isEqualTo(name)
        assertThat(memberCount(name)).isEqualTo(1)
        assertThat(result.responseHeaders[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
        assertThat(result.responseHeaders.getCacheControl()).isEqualTo("no-store")
    }

    @Test
    fun `O6 EXISTING 가입은 대소문자 무시로 그룹을 찾고 만든 사람의 표기를 돌려준다`() {
        val name = uniqueGroupName("Team-A")
        signUp(uniqueLoginId(), name)
        val joiner = uniqueLoginId()

        val result = signUp(joiner, name.lowercase(), "EXISTING")

        assertThat(result.status.value()).describedAs("본문 %s", result.responseBody).isEqualTo(201)
        assertThat(result.responseBody!!["groupName"]).isEqualTo(name)
        assertThat(memberCount(name)).isEqualTo(2)
        val sameGroup = queryLong(
            "SELECT count(*) FROM users u JOIN groups g ON g.id = u.group_id WHERE u.login_id = :id AND g.name = :name",
            "id" to joiner, "name" to name,
        )
        assertThat(sameGroup).isEqualTo(1)
    }

    @Test
    fun `O3 NFD 로 넣은 그룹명은 NFC 로 저장된다`() {
        val nfc = "팀원" + uniqueGroupName("")
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        assertThat(nfd).isNotEqualTo(nfc)

        val result = signUp(uniqueLoginId(), nfd)

        assertThat(result.status.value()).isEqualTo(201)
        assertThat(result.responseBody!!["groupName"]).isEqualTo(nfc)
        assertThat(queryLong("SELECT count(*) FROM groups WHERE name = :name", "name" to nfc)).isEqualTo(1)
    }

    // ---- O7 ~ O11 오류 코드와 우선순위 ----

    @Test
    fun `O7 같은 그룹명 대소문자만 달라도 GROUP_NAME_TAKEN`() {
        val name = uniqueGroupName("Team-A")
        signUp(uniqueLoginId(), name)
        val groupsBefore = count("groups")

        listOf(name, name.lowercase(), name.uppercase()).forEach { variant ->
            signUp(uniqueLoginId(), variant).assertError(409, "GROUP_NAME_TAKEN")
        }
        assertThat(count("groups")).isEqualTo(groupsBefore)
    }

    @Test
    fun `O8 EXISTING 으로 없는 그룹이면 404 GROUP_NOT_FOUND`() {
        val usersBefore = count("users")
        val groupsBefore = count("groups")
        signUp(uniqueLoginId(), uniqueGroupName("Nowhere"), "EXISTING").assertError(404, "GROUP_NOT_FOUND")
        assertThat(count("users")).isEqualTo(usersBefore)
        assertThat(count("groups")).isEqualTo(groupsBefore)
    }

    @Test
    fun `O9 10명 그룹(07시 이후 가입자 포함)에 11번째 EXISTING 은 GROUP_FULL`() {
        val name = uniqueGroupName("Full")
        val members = groupOf(name, 10)
        // 정원은 전체 소속 인원이다 — 07:00 이후 가입자도 센다(prd D12)
        runBlocking {
            db.sql("UPDATE users SET joined_at = :at WHERE login_id IN (:ids)")
                .bind("at", OffsetDateTime.parse("2026-11-11T07:30:00+09:00"))
                .bind("ids", members.takeLast(3))
                .fetch().rowsUpdated().awaitSingle()
        }

        signUp(uniqueLoginId(), name, "EXISTING").assertError(409, "GROUP_FULL")
        assertThat(memberCount(name)).isEqualTo(10)
        assertThat(userCountOf(name)).isEqualTo(10)
    }

    @Test
    fun `O10 아이디 중복은 LOGIN_ID_TAKEN 이고 그룹 생성 · 인원 증가가 남지 않는다`() {
        val taken = uniqueLoginId()
        val existing = uniqueGroupName("Base")
        signUp(taken, existing)
        val fresh = uniqueGroupName("Fresh")

        signUp(taken, fresh).assertError(409, "LOGIN_ID_TAKEN")
        assertThat(memberCount(fresh)).describedAs("Fresh 그룹 행").isNull()

        signUp(taken, existing, "EXISTING").assertError(409, "LOGIN_ID_TAKEN")
        assertThat(memberCount(existing)).isEqualTo(1)
    }

    @Test
    fun `O11 오류가 겹치면 INVALID_REQUEST → 그룹 단계 → LOGIN_ID_TAKEN 순서`() {
        val taken = uniqueLoginId()
        val full = uniqueGroupName("Full")
        groupOf(full, 10)
        signUp(taken, uniqueGroupName("Mine"))
        val existing = uniqueGroupName("Have")
        signUp(uniqueLoginId(), existing)

        signUp(taken, full, "EXISTING").assertError(409, "GROUP_FULL")
        signUp(taken, existing).assertError(409, "GROUP_NAME_TAKEN")
        signUp("BAD ID", uniqueGroupName("Nowhere"), "EXISTING").assertError(400, "INVALID_REQUEST")
    }

    // ---- O12 · O13 저장 값 ----

    @Test
    fun `O12 비밀번호는 BCrypt cost 10 해시로만 저장된다`() {
        val loginId = uniqueLoginId()
        signUp(loginId, uniqueGroupName())

        val hash = runBlocking {
            db.sql("SELECT password_hash FROM users WHERE login_id = :id").bind("id", loginId)
                .map { row -> row.get("password_hash", String::class.java)!! }.one().awaitSingle()
        }
        assertThat(hash).matches("^\\$2[aby]\\$10\\$.{53}$").isNotEqualTo(PASSWORD)
    }

    @Test
    fun `O13 joinedAt 은 DB joined_at 과 같은 순간이고 오프셋은 +09시`() {
        val loginId = uniqueLoginId()
        val result = signUp(loginId, uniqueGroupName())

        val joinedAt = result.responseBody!!["joinedAt"] as String
        assertThat(joinedAt).endsWith("+09:00")
        val stored = runBlocking {
            db.sql("SELECT joined_at FROM users WHERE login_id = :id").bind("id", loginId)
                .map { row -> row.get("joined_at", OffsetDateTime::class.java)!! }.one().awaitSingle()
        }
        // 응답은 DB 값을 자르지 않고 그대로 옮긴다(PostgreSQL 마이크로초까지 같다)
        assertThat(OffsetDateTime.parse(joinedAt).toInstant()).isEqualTo(stored.toInstant())
    }

    @Test
    fun `O34 firstGameDate 는 joinedAt 의 KST 시각이 06시50분 전이면 그 날짜 아니면 다음 날`() {
        val result = signUp(uniqueLoginId(), uniqueGroupName())

        val joinedAt = OffsetDateTime.parse(result.responseBody!!["joinedAt"] as String)
        val firstGameDate = result.responseBody!!["firstGameDate"] as String
        assertThat(firstGameDate).matches("^\\d{4}-\\d{2}-\\d{2}$")
        // 가입은 DB now() 라 시각을 고를 수 없다 — 경계 표는 GameClockTest(O36) 가 본다. 여기서는 응답이 같은 규칙인지만
        val kst = joinedAt.atZoneSameInstant(ZoneId.of("Asia/Seoul"))
        val expected = if (kst.toLocalTime() < LocalTime.of(6, 50)) kst.toLocalDate() else kst.toLocalDate().plusDays(1)
        assertThat(LocalDate.parse(firstGameDate)).isEqualTo(expected)
    }

    /** ORACLE I5 는 O5 ~ O11 뒤에 본다 — 이 클래스의 마지막에 돈다(동시성 쪽은 AuthConcurrencyTest 가 테스트마다 본다). */
    @Test
    @Order(Int.MAX_VALUE)
    fun `I5 member_count 는 언제나 소속 유저 수와 같고 10 이하`() {
        assertThat(brokenGroupCount()).isZero()
    }
}
