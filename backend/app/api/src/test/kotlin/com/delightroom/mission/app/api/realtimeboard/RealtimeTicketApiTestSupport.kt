package com.delightroom.mission.app.api.realtimeboard

import com.delightroom.mission.app.api.auth.AuthCookies
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.springframework.data.redis.core.ScanOptions
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** 티켓 발급 API 테스트의 바탕 — `tk:*` 를 세고, 테스트마다 지운다(Redis 컨테이너는 api 테스트 JVM 하나가 같이 쓴다). */
abstract class RealtimeTicketApiTestSupport : BoardApiTestSupport() {
    protected val json = jacksonObjectMapper()

    @AfterEach
    fun cleanUpTickets(): Unit = runBlocking {
        val tickets = ticketKeys()
        if (tickets.isNotEmpty()) redis.delete(*tickets.toTypedArray()).awaitSingle()
        Unit
    }

    protected fun ticketKeys(): List<String> = runBlocking {
        redis.scan(ScanOptions.scanOptions().match("tk:*").count(1_000).build()).collectList().awaitSingle()
    }

    protected fun postTicket(access: String?): WebTestClient.ResponseSpec =
        client.post().uri(TICKETS)
            .apply { if (access != null) cookie(AuthCookies.ACCESS, access) }
            .exchange()

    protected fun body(spec: WebTestClient.ResponseSpec, status: Int): Map<String, Any?> {
        val raw = spec.expectStatus().isEqualTo(status).expectBody(String::class.java).returnResult().responseBody!!
        return json.readValue(raw)
    }

    companion object {
        const val TICKETS = "/api/v1/realtime/tickets"
        val TICKET_SHAPE = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
