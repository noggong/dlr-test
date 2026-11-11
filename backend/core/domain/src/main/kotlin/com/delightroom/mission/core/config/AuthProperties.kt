package com.delightroom.mission.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 인증 설정(auth-signup LLD §1 · §2.3). `jwtSecret` 은 HS256 키 — UTF-8 32바이트(256비트) 미만이면 바인딩 = 기동이 실패한다.
 * 짧은 키를 기동 때 막아야 로그인이 `SET NX` 뒤 서명에서 실패해 세션이 잠기는 일(LLD §5)이 없다.
 *
 * `api` 만 켠다(`AuthWebConfiguration`) — `realtime` 은 JWT 키를 갖지 않는다.
 */
@ConfigurationProperties("auth")
data class AuthProperties(
    val jwtSecret: String,
    val accessTtl: Duration = Duration.ofHours(1),
    val refreshTtl: Duration = Duration.ofHours(2),
    val bcryptCost: Int = 10,
    val cookieSecure: Boolean = true,
) {
    init {
        require(jwtSecret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "auth.jwt-secret(AUTH_JWT_SECRET) 은 UTF-8 ${MIN_SECRET_BYTES}바이트 이상이어야 한다"
        }
        require(!accessTtl.isNegative && !accessTtl.isZero && accessTtl.nano == 0) { "auth.access-ttl 은 양의 초 단위다: $accessTtl" }
        require(!refreshTtl.isNegative && !refreshTtl.isZero && refreshTtl.nano == 0) { "auth.refresh-ttl 은 양의 초 단위다: $refreshTtl" }
        require(bcryptCost in 4..31) { "auth.bcrypt-cost 는 4~31 이다: $bcryptCost" }
    }

    /** 키 값이 로그 · 예외 메시지에 실리지 않게 한다. */
    override fun toString(): String =
        "AuthProperties(jwtSecret=***, accessTtl=$accessTtl, refreshTtl=$refreshTtl, bcryptCost=$bcryptCost, cookieSecure=$cookieSecure)"

    companion object {
        const val MIN_SECRET_BYTES = 32
    }
}
