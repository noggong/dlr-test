package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.PlainHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

/** ORACLE auth-signup O16 — 발급 토큰을 디코드해 클레임 · 수명 · 알고리즘을 본다. 검증(`verify*`)의 거절 갈래도 함께. */
class JwtTokenIssuerTest {
    private val secret = "mission-local-jwt-secret-not-for-production"
    private val issuer = JwtTokenIssuer(AuthProperties(jwtSecret = secret))
    private val account = Account(UserId(42), LoginId("alice_01"), "\$2a\$10\$x", GroupId(7), GroupName("새벽반"))
    private val jti = "3f2b6c1e-8a4d-4f0e-9b7a-1c2d3e4f5a6b"

    /** 06:40:00.750 KST — 초 아래는 버려져 `iat` = 06:40:00 이다. */
    private val now = OffsetDateTime.parse("2026-11-11T06:40:00.750+09:00").toInstant()
    private val iat = OffsetDateTime.parse("2026-11-11T06:40:00+09:00").toInstant()

    private val tokens = issuer.issue(account, jti, now)

    @Test
    fun `O16 access 클레임 키는 sub lid gid iat exp 이고 수명 3600초 · alg HS256`() {
        val jwt = SignedJWT.parse(tokens.access)
        val claims = jwt.jwtClaimsSet

        assertThat(jwt.header.toJSONObject()).isEqualTo(mapOf("alg" to "HS256"))
        assertThat(claims.claims.keys).containsExactlyInAnyOrder("sub", "lid", "gid", "iat", "exp")
        assertThat(claims.subject).isEqualTo("42")
        assertThat(claims.getStringClaim("lid")).isEqualTo("alice_01")
        assertThat(claims.getStringClaim("gid")).isEqualTo("7")
        assertThat(claims.issueTime.toInstant()).isEqualTo(iat)
        assertThat(claims.expirationTime.time / 1000 - claims.issueTime.time / 1000).isEqualTo(3600)
        assertThat(tokens.accessExpiresAt).isEqualTo(claims.expirationTime.toInstant())
    }

    @Test
    fun `O16 refresh 클레임 키는 sub jti iat exp 이고 수명 7200초 · alg HS256`() {
        val jwt = SignedJWT.parse(tokens.refresh)
        val claims = jwt.jwtClaimsSet

        assertThat(jwt.header.toJSONObject()).isEqualTo(mapOf("alg" to "HS256"))
        assertThat(claims.claims.keys).containsExactlyInAnyOrder("sub", "jti", "iat", "exp")
        assertThat(claims.subject).isEqualTo("42")
        assertThat(claims.jwtid).isEqualTo(jti)
        assertThat(claims.issueTime.toInstant()).isEqualTo(iat)
        assertThat(claims.expirationTime.time / 1000 - claims.issueTime.time / 1000).isEqualTo(7200)
        assertThat(tokens.refreshExpiresAt).isEqualTo(claims.expirationTime.toInstant())
    }

    @Test
    fun `발급한 토큰은 exp 직전까지 검증을 통과한다`() {
        val access = issuer.verifyAccess(tokens.access, tokens.accessExpiresAt.minusMillis(1))
        val refresh = issuer.verifyRefresh(tokens.refresh, tokens.refreshExpiresAt.minusMillis(1))

        assertThat(access?.userId).isEqualTo(UserId(42))
        assertThat(access?.loginId).isEqualTo(LoginId("alice_01"))
        assertThat(access?.groupId).isEqualTo(GroupId(7))
        assertThat(access?.expiresAt).isEqualTo(tokens.accessExpiresAt)
        assertThat(refresh?.userId).isEqualTo(UserId(42))
        assertThat(refresh?.jti).isEqualTo(jti)
        assertThat(refresh?.expiresAt).isEqualTo(tokens.refreshExpiresAt)
    }

    @Test
    fun `exp 시각부터는 만료 — now 가 exp 와 같으면 null`() {
        assertThat(issuer.verifyAccess(tokens.access, tokens.accessExpiresAt)).isNull()
        assertThat(issuer.verifyRefresh(tokens.refresh, tokens.refreshExpiresAt)).isNull()
    }

    @Test
    fun `access 와 refresh 는 서로의 자리에서 검증에 실패한다`() {
        assertThat(issuer.verifyAccess(tokens.refresh, now)).isNull()
        assertThat(issuer.verifyRefresh(tokens.access, now)).isNull()
    }

    @Test
    fun `다른 키로 서명했거나 내용을 바꾼 토큰은 null`() {
        val other = JwtTokenIssuer(AuthProperties(jwtSecret = "another-secret-that-is-at-least-32-bytes"))
        assertThat(issuer.verifyAccess(other.issue(account, jti, now).access, now)).isNull()

        val (header, payload, signature) = tokens.access.split('.')
        val tampered = SignedJWT.parse(tokens.access).jwtClaimsSet.toJSONObject().apply { put("gid", "8") }
        val forgedPayload = com.nimbusds.jose.util.Base64URL.encode(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(tampered))
        assertThat(issuer.verifyAccess("$header.$forgedPayload.$signature", now)).isNull()
        assertThat(issuer.verifyAccess("$header.$payload.", now)).isNull()
    }

    @Test
    fun `alg none · 다른 알고리즘 · JWS 가 아닌 문자열은 null`() {
        val claims = SignedJWT.parse(tokens.access).jwtClaimsSet
        assertThat(issuer.verifyAccess(PlainJWT(PlainHeader(), claims).serialize(), now)).isNull()

        val hs512 = SignedJWT(JWSHeader(JWSAlgorithm.HS512), claims)
        hs512.sign(MACSigner((secret + secret).toByteArray()))
        assertThat(issuer.verifyAccess(hs512.serialize(), now)).isNull()

        assertThat(issuer.verifyAccess("", now)).isNull()
        assertThat(issuer.verifyAccess("not-a-jwt", now)).isNull()
        assertThat(issuer.verifyRefresh("a.b.c", now)).isNull()
    }

    @Test
    fun `같은 키로 서명했어도 클레임이 빠졌거나 타입이 다르면 null`() {
        fun signed(builder: JWTClaimsSet.Builder): String =
            SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build()).apply { sign(MACSigner(secret.toByteArray())) }.serialize()
        val exp = Date.from(now.plusSeconds(60))

        assertThat(issuer.verifyAccess(signed(JWTClaimsSet.Builder().subject("42").claim("lid", "alice_01").expirationTime(exp)), now)).isNull()
        assertThat(issuer.verifyAccess(signed(JWTClaimsSet.Builder().subject("42").claim("lid", "alice_01").claim("gid", 7).expirationTime(exp)), now)).isNull()
        assertThat(issuer.verifyAccess(signed(JWTClaimsSet.Builder().subject("x").claim("lid", "alice_01").claim("gid", "7").expirationTime(exp)), now)).isNull()
        assertThat(issuer.verifyAccess(signed(JWTClaimsSet.Builder().subject("42").claim("lid", "alice_01").claim("gid", "7")), now)).isNull()
        assertThat(issuer.verifyAccess(signed(JWTClaimsSet.Builder().subject("42").claim("lid", "alice_01").claim("gid", "7").jwtID(jti).expirationTime(exp)), now)).isNull()
        assertThat(issuer.verifyRefresh(signed(JWTClaimsSet.Builder().subject("42").expirationTime(exp)), now)).isNull()
        assertThat(issuer.verifyRefresh(signed(JWTClaimsSet.Builder().jwtID(jti).expirationTime(exp)), now)).isNull()
    }

    @Test
    fun `iat 는 now 를 초로 자른 값 — 같은 초 안의 두 발급은 만료 시각이 같다`() {
        val later = issuer.issue(account, jti, now.plusMillis(200))

        assertThat(later.accessExpiresAt).isEqualTo(tokens.accessExpiresAt)
        assertThat(later.accessExpiresAt).isEqualTo(Instant.parse("2026-11-10T22:40:00Z"))
        assertThat(later.refreshExpiresAt).isEqualTo(Instant.parse("2026-11-10T23:40:00Z"))
    }
}
