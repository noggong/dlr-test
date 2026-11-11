package com.delightroom.mission.app.api.gamesession

import com.delightroom.mission.app.api.auth.AuthCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime

/**
 * ORACLE game-session O10 · O11(진입 쪽) · O17 ~ O19 · O21 ~ O24 · I3 — 진입 신호(SPEC §2.3 · GS9 ~ GS13 · LLD §3.3 · §4.3).
 * 게임 시각은 `D 07:00:01` 에서 시작해 흐른다(창 안). 시간 창 밖(O20)은 [QuestionBankBeforeOpenTest] · [QuestionBankClosedTest].
 * 시작은 문제은행 API 로 하고, 시작 뒤 상태(완료 · 진행도)는 그룹 Hash 에 직접 적는다 — solve-signal 의 반영은 그 기능이 본다.
 */
class ShownTest : QuestionBankApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun offset(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(7, 0, 1))
    }

    /** 은행에서 난이도 [difficulty] 의 [index] 번째 문제 PK. */
    private fun bankPk(bank: Map<String, Any>, difficulty: Int, index: Int = 0): String =
        questions(bank).filter { it["difficulty"] == difficulty }[index]["questionId"] as String

    private fun shownEvents(fixture: Fixture, member: Member): List<Map<String, String>> =
        events(fixture).filter { it["type"] == "question.shown" && it["uid"] == member.id.value.toString() }

    private fun setFields(fixture: Fixture, vararg fields: Pair<String, String>) = runBlocking {
        redis.opsForHash<String, String>().putAll(redisKeys.group(fixture.gid, D), fields.toMap()).awaitSingleOrNull()
    }

    @Test
    fun `O10 06시50분 부터 가입한 유저의 진입 신호는 NOT_PARTICIPANT — 그룹 Hash 에 그 유저 필드 0개`(): Unit = runBlocking {
        val a = groupA()
        val q = questionRows().values.first { it.difficulty == 1 }.pk

        val result = postShown(a, a["late"], 1, q)

        assertThat(result.status.value()).isEqualTo(403)
        assertThat(result.responseBody!!["code"]).isEqualTo("NOT_PARTICIPANT")
        val uid = a["late"].id.value
        assertThat(groupHash(a)).isNotEmpty() // 명단은 그 자리에서 만들어졌다(다른 그룹원 3명)
        assertThat(groupHash(a).keys.filter { it.startsWith("$uid:") }).isEmpty()
        assertThat(events(a)).isEmpty()
    }

    @Test
    fun `O11 1명 그룹의 진입 신호는 SOLO_GROUP — 그룹 Hash 없음`(): Unit = runBlocking {
        val s = group("solo" to "${D}T06:00:00+09:00")
        val q = questionRows().values.first { it.difficulty == 1 }.pk

        val result = postShown(s, s["solo"], 1, q)

        assertThat(result.status.value()).isEqualTo(409)
        assertThat(result.responseBody!!["code"]).isEqualTo("SOLO_GROUP")
        assertThat(hasKey(redisKeys.group(s.gid, D))).isFalse()
        val day = redisKeys.dayGroups(redisKeys.bucket(s.gid), D)
        assertThat(redis.opsForSet().isMember(day, s.gid.value.toString()).awaitSingle()).isFalse()
        assertThat(events(s)).isEmpty()
    }

    @Test
    fun `O17 진입 신호로 지금 문제 고정 — 200 본문 · cur = q · question_shown 1건(qid = questions_id) · version 변화 0`(): Unit = runBlocking {
        val a = groupA()
        val q = bankPk(postBank(a, a["alice"]).responseBody!!, 1)
        val version = groupHash(a)["version"]

        val result = postShown(a, a["alice"], 1, q)

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody).isEqualTo(mapOf("questionNo" to 1, "questionId" to q, "progress" to 0, "missionStatus" to "IN_PROGRESS"))
        val uid = a["alice"].id.value
        assertThat(groupHash(a)).containsEntry("$uid:cur", q).containsEntry("version", version)
        val e = shownEvents(a, a["alice"]).single()
        assertThat(e).containsEntry("questionNo", "1").containsEntry("questionId", q)
            .containsEntry("qid", questionRows().values.single { it.pk == q }.id.toString())
            .containsEntry("lid", a["alice"].loginId).containsEntry("version", version).containsEntry("date", D.toString())
        assertThat(e["shownAt"]).isEqualTo(e["at"])
    }

    @Test
    fun `O18 진입 신호 멱등 — 같은 요청 2번 더, 3개 응답 같음 · question_shown 여전히 1건`(): Unit = runBlocking {
        val a = groupA()
        val q = bankPk(postBank(a, a["alice"]).responseBody!!, 1)

        val bodies = (1..3).map { postShown(a, a["alice"], 1, q) }

        assertThat(bodies.map { it.status.value() }).containsOnly(200)
        assertThat(bodies.map { it.responseBody }.toSet()).hasSize(1)
        assertThat(shownEvents(a, a["alice"])).hasSize(1)
    }

    @Test
    fun `O19 본문 검증 8가지 모두 INVALID_REQUEST — Lua 호출 0(Hash · Stream 변화 0)`(): Unit = runBlocking {
        val a = groupA()
        val q = bankPk(postBank(a, a["alice"]).responseBody!!, 1)
        val hashBefore = groupHash(a)
        val eventsBefore = events(a)
        val bodies = listOf(
            """{"questionNo":0,"questionId":"$q"}""",
            """{"questionNo":4,"questionId":"$q"}""",
            """{"questionNo":"1","questionId":"$q"}""",
            """{"questionId":"$q"}""",
            """{"questionNo":1,"questionId":"${q.dropLast(1)}"}""",
            """{"questionNo":1,"questionId":"${q.uppercase().also { assertThat(it).isNotEqualTo(q) }}"}""",
            """{"questionNo":1}""",
            "questionNo=1",
        )
        val token = access(a, a["alice"])

        for (body in bodies) {
            val r = postShownRaw(token, body)
            assertThat(r.status.value()).describedAs(body).isEqualTo(400)
            assertThat(r.responseBody!!["code"]).describedAs(body).isEqualTo("INVALID_REQUEST")
        }
        assertThat(shownCalls.calls(a["alice"].id)).isZero()
        assertThat(groupHash(a)).isEqualTo(hashBefore)
        assertThat(events(a)).isEqualTo(eventsBefore)
    }

    @Test
    fun `O21 시작 전은 MISSION_NOT_STARTED · SUCCEEDED · CHEAT_FAILED 는 MISSION_ALREADY_FINISHED`(): Unit = runBlocking {
        val a = groupA()
        val q = questionRows().values.first { it.difficulty == 1 }.pk

        val notStarted = postShown(a, a["bob"], 1, q)

        assertThat(notStarted.status.value()).isEqualTo(409)
        assertThat(notStarted.responseBody!!["code"]).isEqualTo("MISSION_NOT_STARTED")
        for ((who, st) in listOf("alice" to "SUCCEEDED", "carol" to "CHEAT_FAILED")) {
            val m = a[who]
            val pk = bankPk(postBank(a, m).responseBody!!, 1)
            setFields(a, "${m.id.value}:st" to st)

            val r = postShown(a, m, 1, pk)

            assertThat(r.status.value()).describedAs(st).isEqualTo(409)
            assertThat(r.responseBody!!["code"]).describedAs(st).isEqualTo("MISSION_ALREADY_FINISHED")
        }
        assertThat(events(a).filter { it["type"] == "question.shown" }).isEmpty()
    }

    @Test
    fun `O22 QUESTION_MISMATCH 세 경우 — 없는 PK · 난이도 2 문제로 1번 · 1번에 q 고정 뒤 다른 r, cur = q 그대로`(): Unit = runBlocking {
        val a = groupA()
        val bank = postBank(a, a["alice"]).responseBody!!
        val pks = questionRows().values.map { it.pk }.toSet()
        val missing = "f".repeat(64).also { assertThat(pks).doesNotContain(it) }
        val q = bankPk(bank, 1, 0)
        val r = bankPk(bank, 1, 1)

        val unknown = postShown(a, a["alice"], 1, missing)
        val wrongDifficulty = postShown(a, a["alice"], 1, bankPk(bank, 2))
        assertThat(postShown(a, a["alice"], 1, q).status.value()).isEqualTo(200)
        val other = postShown(a, a["alice"], 1, r)

        for (res in listOf(unknown, wrongDifficulty, other)) {
            assertThat(res.status.value()).isEqualTo(409)
            assertThat(res.responseBody!!["code"]).isEqualTo("QUESTION_MISMATCH")
        }
        assertThat(groupHash(a)).containsEntry("${a["alice"].id.value}:cur", q)
        assertThat(shownEvents(a, a["alice"])).hasSize(1)
    }

    @Test
    fun `O22 ④ 패스로 넘긴 문제의 늦은 진입 신호는 QUESTION_MISMATCH · cur 없음, 이어진 교체 문제 진입은 200 · cur = r`(): Unit = runBlocking {
        val a = groupA()
        val bank = postBank(a, a["alice"]).responseBody!!
        val q = bankPk(bank, 1, 0)
        val r = bankPk(bank, 1, 1)
        assertThat(postShown(a, a["alice"], 1, q).status.value()).isEqualTo(200)
        val pass = client.post().uri("/api/v1/games/today/passes").cookie(AuthCookies.ACCESS, access(a, a["alice"]))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body(1, q)).exchange().expectBody(MAP_TYPE).returnResult()
        assertThat(pass.status.value()).isEqualTo(200)

        val stale = postShown(a, a["alice"], 1, q)

        assertThat(stale.status.value()).isEqualTo(409)
        assertThat(stale.responseBody!!["code"]).isEqualTo("QUESTION_MISMATCH")
        val uid = a["alice"].id.value
        assertThat(groupHash(a)).doesNotContainKey("$uid:cur")
        val replacement = postShown(a, a["alice"], 1, r)
        assertThat(replacement.status.value()).isEqualTo(200)
        assertThat(groupHash(a)).containsEntry("$uid:cur", r)
        assertThat(shownEvents(a, a["alice"]).map { it["questionId"] }).containsExactly(q, r)
    }

    @Test
    fun `O23 QUESTION_OUT_OF_ORDER — 진행도 0 에 2번 · 진행도 1 에 1번(늦은 재시도)`(): Unit = runBlocking {
        val a = groupA()
        val bank = postBank(a, a["alice"]).responseBody!!

        val ahead = postShown(a, a["alice"], 2, bankPk(bank, 2))
        setFields(a, "${a["alice"].id.value}:p" to "1")
        val late = postShown(a, a["alice"], 1, bankPk(bank, 1))

        for (res in listOf(ahead, late)) {
            assertThat(res.status.value()).isEqualTo(409)
            assertThat(res.responseBody!!["code"]).isEqualTo("QUESTION_OUT_OF_ORDER")
        }
        assertThat(shownEvents(a, a["alice"])).isEmpty()
    }

    @Test
    fun `O24 진입 신호 판정 순서 — 시작 전 + 없는 PK 는 MISSION_NOT_STARTED, 시작 뒤 + 없는 PK + 3번은 QUESTION_MISMATCH`(): Unit = runBlocking {
        val a = groupA()
        postBank(a, a["alice"])
        val missing = "e".repeat(64)

        val beforeStart = postShown(a, a["bob"], 1, missing)
        val afterStart = postShown(a, a["alice"], 3, missing)

        assertThat(beforeStart.status.value()).isEqualTo(409)
        assertThat(beforeStart.responseBody!!["code"]).isEqualTo("MISSION_NOT_STARTED")
        assertThat(afterStart.status.value()).isEqualTo(409)
        assertThat(afterStart.responseBody!!["code"]).isEqualTo("QUESTION_MISMATCH")
    }

    @Test
    fun `I3 번호마다 지금 문제 하나 — 같은 번호 · 다른 PK 2건 동시 × 50회, 200 정확히 1 · cur = 그 PK · question_shown 1건`(): Unit = runBlocking {
        repeat(50) { round ->
            val g = group("alice" to "${D}T06:00:00+09:00", "bob" to "${D}T06:00:00+09:00")
            val bank = postBank(g, g["alice"]).responseBody!!
            val token = access(g, g["alice"])
            val candidates = listOf(bankPk(bank, 1, 0), bankPk(bank, 1, 1))

            val results = coroutineScope {
                val io = Dispatchers.IO.limitedParallelism(2)
                candidates.map { pk -> async(io) { pk to postShownRaw(token, body(1, pk)) } }.awaitAll()
            }

            val ok = results.filter { it.second.status.value() == 200 }
            assertThat(ok).describedAs("round $round").hasSize(1)
            val lost = results.single { it.second.status.value() != 200 }.second
            assertThat(lost.status.value()).describedAs("round $round").isEqualTo(409)
            assertThat(lost.responseBody!!["code"]).describedAs("round $round").isEqualTo("QUESTION_MISMATCH")
            assertThat(groupHash(g)["${g["alice"].id.value}:cur"]).describedAs("round $round").isEqualTo(ok.single().first)
            assertThat(shownEvents(g, g["alice"])).describedAs("round $round").hasSize(1)
        }
    }
}
