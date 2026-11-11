package com.delightroom.mission.app.api.auth

import com.delightroom.mission.core.config.AuthProperties
import com.delightroom.mission.core.domain.auth.AccessClaims
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.IssuedTokens
import com.delightroom.mission.core.domain.auth.RefreshClaims
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.TokenIssuer
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * JWS HS256 토큰(auth-signup LLD §2.3). 헤더는 `{"alg":"HS256"}` 하나.
 *
 * - access = `sub`(users.id) · `lid` · `gid` · `iat` · `exp`(+[AuthProperties.accessTtl])
 * - refresh = `sub` · `jti` · `iat` · `exp`(+[AuthProperties.refreshTtl])
 *
 * 두 토큰은 클레임 모양으로 갈린다 — refresh 를 access 로(또는 반대로) 넣으면 검증이 `null` 이다.
 */
class JwtTokenIssuer(private val properties: AuthProperties) : TokenIssuer {
    private val secret = properties.jwtSecret.toByteArray(Charsets.UTF_8)
    private val signer = MACSigner(secret)
    private val verifier = MACVerifier(secret)

    override fun issue(account: Account, jti: String, now: Instant): IssuedTokens {
        val iat = now.truncatedTo(ChronoUnit.SECONDS)
        val accessExp = iat.plus(properties.accessTtl)
        val refreshExp = iat.plus(properties.refreshTtl)
        val access = JWTClaimsSet.Builder()
            .subject(account.userId.value.toString())
            .claim(LID, account.loginId.value)
            .claim(GID, account.groupId.value.toString())
            .issueTime(Date.from(iat))
            .expirationTime(Date.from(accessExp))
            .build()
        val refresh = JWTClaimsSet.Builder()
            .subject(account.userId.value.toString())
            .jwtID(jti)
            .issueTime(Date.from(iat))
            .expirationTime(Date.from(refreshExp))
            .build()
        return IssuedTokens(sign(access), accessExp, sign(refresh), refreshExp)
    }

    override fun verifyAccess(token: String, now: Instant): AccessClaims? {
        val claims = verified(token, now) ?: return null
        if (claims.jwtid != null) return null
        return parseOrNull {
            AccessClaims(
                userId = UserId(claims.subject?.toLongOrNull() ?: return null),
                loginId = LoginId(claims.getStringClaim(LID) ?: return null),
                groupId = GroupId(claims.getStringClaim(GID)?.toLongOrNull() ?: return null),
                expiresAt = claims.expirationTime.toInstant(),
            )
        }
    }

    override fun verifyRefresh(token: String, now: Instant): RefreshClaims? {
        val claims = verified(token, now) ?: return null
        if (claims.claims.containsKey(LID)) return null
        return RefreshClaims(
            userId = UserId(claims.subject?.toLongOrNull() ?: return null),
            jti = claims.jwtid ?: return null,
            expiresAt = claims.expirationTime.toInstant(),
        )
    }

    private fun sign(claims: JWTClaimsSet): String =
        SignedJWT(HEADER, claims).apply { sign(signer) }.serialize()

    /** 서명 · 알고리즘 · 만료(`now < exp`)를 통과한 클레임. 아니면 `null`. */
    private fun verified(token: String, now: Instant): JWTClaimsSet? =
        parseOrNull {
            val jwt = SignedJWT.parse(token)
            if (jwt.header.algorithm != JWSAlgorithm.HS256 || !jwt.verify(verifier)) return null
            val claims = jwt.jwtClaimsSet
            val exp = claims.expirationTime ?: return null
            if (!now.isBefore(exp.toInstant())) return null
            claims
        }

    /** 모양이 틀린 토큰(JWS 가 아님 · 클레임 타입이 다름)은 `null` 로 — 401 한 갈래로 간다. */
    private inline fun <T> parseOrNull(block: () -> T): T? =
        try {
            block()
        } catch (_: ParseException) {
            null
        } catch (_: JOSEException) {
            null
        }

    private companion object {
        const val LID = "lid"
        const val GID = "gid"
        val HEADER: JWSHeader = JWSHeader(JWSAlgorithm.HS256)
    }
}
