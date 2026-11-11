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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.LocalTime

/**
 * ORACLE game-session O5 · O7 · O10 ~ O16 · I1 · I2 · I6 — 문제은행 = 미션 시작(SPEC §2.2 · GS5 ~ GS8 · GS14 · LLD §3.2).
 * 게임 시각은 `D 07:00:01` 에서 시작해 테스트가 도는 만큼 흐른다(창 안 — 07:00 ~ 08:00).
 * 시간 창 밖(O8 · O9)은 [QuestionBankBeforeOpenTest] · [QuestionBankClosedTest].
 */
class QuestionBankTest : QuestionBankApiTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun offset(registry: DynamicPropertyRegistry) = offsetTo(registry, LocalTime.of(7, 0, 1))
    }

    @Test
    fun `O5 첫 문제은행 = 미션 시작 — p 0 · IN_PROGRESS · sa = 응답 · version 1 증가 · mission_started 1건`(): Unit = runBlocking {
        val a = groupA()

        val result = postBank(a, a["alice"])

        assertThat(result.status.value()).isEqualTo(200)
        val body = result.responseBody!!
        val startedAt = startedAt(body)
        assertThat(startedAt).isAfterOrEqualTo(at(LocalTime.of(7, 0))).isBefore(at(LocalTime.of(8, 0)))
        assertThat(body["missionStartedAt"] as String).endsWith("+09:00")
        val uid = a["alice"].id.value
        val hash = groupHash(a)
        assertThat(hash).containsEntry("$uid:p", "0").containsEntry("$uid:st", "IN_PROGRESS")
            .containsEntry("$uid:sa", micros(startedAt).toString())
        // 명단을 그 자리에서 만들며 version 0 → 시작으로 1
        assertThat(hash["version"]).isEqualTo("1")
        assertThat(missionStarted(a, a["alice"])).isEqualTo(1)
        assertThat(events(a).single { it["type"] == "mission.started" }["startedAt"]).isEqualTo(micros(startedAt).toString())
        assertThat(questions(body)).isNotEmpty()
    }

    @Test
    fun `O7 같은 날 재요청은 같은 시작 · 같은 은행(순서까지) — mission_started 여전히 1건 · version 변화 0`(): Unit = runBlocking {
        val a = groupA()
        val first = postBank(a, a["alice"]).responseBody!!
        val version = groupHash(a)["version"]

        val again = (1..2).map { postBank(a, a["alice"]) }

        for (r in again) {
            assertThat(r.status.value()).isEqualTo(200)
            assertThat(r.responseBody!!["missionStartedAt"]).isEqualTo(first["missionStartedAt"])
            assertThat(questions(r.responseBody!!)).isEqualTo(questions(first))
        }
        assertThat(missionStarted(a, a["alice"])).isEqualTo(1)
        assertThat(groupHash(a)["version"]).isEqualTo(version)
    }

    @Test
    fun `O10 07시 이후 가입자는 NOT_PARTICIPANT — 그룹 Hash 에 그 유저 필드 0개 · 은행 키 없음`(): Unit = runBlocking {
        val a = groupA()

        val result = postBank(a, a["late"])

        assertThat(result.status.value()).isEqualTo(403)
        assertThat(result.responseBody!!["code"]).isEqualTo("NOT_PARTICIPANT")
        val uid = a["late"].id.value
        assertThat(groupHash(a).keys.filter { it.startsWith("$uid:") }).isEmpty()
        assertThat(groupHash(a)).isNotEmpty() // 명단 · 그룹 Hash 는 만들어졌다(다른 그룹원 3명)
        assertThat(hasKey(bankKey(a["late"]))).isFalse()
    }

    @Test
    fun `O11 1명 그룹은 SOLO_GROUP — 그룹 Hash 없음 · 그날 그룹 목록에 없음`(): Unit = runBlocking {
        val s = group("solo" to "${D}T06:00:00+09:00")

        val result = postBank(s, s["solo"])

        assertThat(result.status.value()).isEqualTo(409)
        assertThat(result.responseBody!!["code"]).isEqualTo("SOLO_GROUP")
        assertThat(hasKey(redisKeys.group(s.gid, D))).isFalse()
        val day = redisKeys.dayGroups(redisKeys.bucket(s.gid), D)
        assertThat(redis.opsForSet().isMember(day, s.gid.value.toString()).awaitSingle()).isFalse()
        assertThat(hasKey(bankKey(s["solo"]))).isFalse()
    }

    @Test
    fun `O12 미출제 0개 난이도가 있으면 QUESTIONS_EXHAUSTED — 은행 키 없음 · 시작은 반영(p 0)`(): Unit = runBlocking {
        val a = groupA()
        // 시드는 난이도마다 120개 — 난이도 3 을 전부 출제 이력에 넣는다(ORACLE 고정 데이터의 30개 전부와 같은 뜻)
        serve(a["bob"], questionRows().values.filter { it.difficulty == 3 }.map { it.id }, no = 3)

        val result = postBank(a, a["bob"])

        assertThat(result.status.value()).isEqualTo(409)
        assertThat(result.responseBody!!["code"]).isEqualTo("QUESTIONS_EXHAUSTED")
        assertThat(hasKey(bankKey(a["bob"]))).isFalse()
        val uid = a["bob"].id.value
        assertThat(groupHash(a)).containsEntry("$uid:p", "0").containsEntry("$uid:st", "IN_PROGRESS")
        assertThat(missionStarted(a, a["bob"])).isEqualTo(1)
    }

    @Test
    fun `O13 은행 구성 — 난이도 1 은 이력 밖 15개 전부 · 2 · 3 은 20개씩, 이력과 겹침 0 · 중복 0 · answer 키 0 · 1 → 2 → 3`(): Unit = runBlocking {
        val a = groupA()
        val rows = questionRows()
        val ones = rows.values.filter { it.difficulty == 1 }.map { it.id }.sorted()
        // 난이도 1 의 15개만 남긴다(ORACLE: 30개 중 15개 이력 — 시드가 120개라 105개를 이력에 넣는다)
        val served = ones.dropLast(15)
        serve(a["carol"], served)

        val raw = client.post().uri(QUESTION_BANK).cookie(AuthCookies.ACCESS, access(a, a["carol"]))
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        // JSON 에 실제 정답 키가 없다(answerHash 만)
        assertThat(Regex("\"answer\"\\s*:").containsMatchIn(raw)).isFalse()
        val body = postBank(a, a["carol"]).responseBody!!

        val byPk = rows.values.associateBy { it.pk }
        val qs = questions(body)
        val difficulties = qs.map { it["difficulty"] as Int }
        assertThat(difficulties).isSorted()
        assertThat(difficulties.count { it == 1 }).isEqualTo(15)
        assertThat(difficulties.count { it == 2 }).isEqualTo(20)
        assertThat(difficulties.count { it == 3 }).isEqualTo(20)
        val ids = qs.map { byPk.getValue(it["questionId"] as String).id }
        assertThat(ids).doesNotHaveDuplicates()
        val oneIds = qs.filter { it["difficulty"] == 1 }.map { byPk.getValue(it["questionId"] as String).id }
        assertThat(oneIds).containsExactlyInAnyOrderElementsOf(ones.takeLast(15))
        assertThat(ids).doesNotContainAnyElementsOf(served)
        for (q in qs) {
            val row = byPk.getValue(q["questionId"] as String)
            assertThat(q["difficulty"]).isEqualTo(row.difficulty)
            assertThat(q["answerHash"]).isEqualTo(row.answerHash)
            assertThat(q.keys).containsExactlyInAnyOrder("questionId", "difficulty", "body", "category", "answerHash")
            assertThat(q["body"] as String).isNotBlank()
            assertThat(q["category"]).isIn("CURRENT", "POLITICS", "SOCIETY", "GENERAL")
        }
    }

    @Test
    fun `O14 미리 만든 은행이 있으면 그대로(순서 같음) — Q2 호출 0`(): Unit = runBlocking {
        val a = groupA()
        val rows = questionRows().values.groupBy { it.difficulty }.mapValues { (_, v) -> v.sortedBy { it.id } }
        val prepared = mapOf(1 to rows.getValue(1).take(3).reversed(), 2 to rows.getValue(2).take(2), 3 to rows.getValue(3).drop(5).take(4))
        val json = prepared.entries.joinToString(",", "{", "}") { (d, v) -> "\"$d\":[${v.joinToString(",") { it.id.toString() }}]" }
        redis.opsForValue().set(bankKey(a["alice"]), json, Duration.ofMinutes(30)).awaitSingle()

        val result = postBank(a, a["alice"])

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(questionIds(result.responseBody!!)).containsExactlyElementsOf(prepared.toSortedMap().values.flatten().map { it.pk })
        assertThat(servedIds.calls(a["alice"].id)).isZero()
        assertThat(redis.opsForValue().get(bankKey(a["alice"])).awaitSingle()).isEqualTo(json)
    }

    @Test
    fun `O15 은행이 없으면 그 자리에서 만들고 08시 만료로 캐시 — 남은 시간 = D 08시 − 게임 시각 ±1초`(): Unit = runBlocking {
        val a = groupA()
        assertThat(hasKey(bankKey(a["alice"]))).isFalse()

        val requestedAt = gameProperties.now(clock)
        val result = postBank(a, a["alice"])

        assertThat(result.status.value()).isEqualTo(200)
        val raw = redis.opsForValue().get(bankKey(a["alice"])).awaitSingle()
        assertThat(raw).startsWith("{\"1\":[")
        assertThat(servedIds.calls(a["alice"].id)).isEqualTo(1)
        val ttl = redis.getExpire(bankKey(a["alice"])).awaitSingle()
        val expected = Duration.between(requestedAt, at(LocalTime.of(8, 0)))
        assertThat(ttl).isBetween(expected.minusSeconds(1), expected.plusSeconds(1))
    }

    @Test
    fun `O16 같은 날 재요청에 오늘 낸 문제가 그대로 있다 — 출제 이력에 들어가도 은행은 같다`(): Unit = runBlocking {
        val a = groupA()
        val first = postBank(a, a["alice"]).responseBody!!
        val q = questions(first).first { it["difficulty"] == 1 }["questionId"] as String
        // 진입 신호(T-GS-4)를 persister 가 옮긴 결과 — 오늘 1번 자리로 q 를 냈다
        val qid = questionRows().values.single { it.pk == q }.id
        serve(a["alice"], listOf(qid), date = D, no = 1)

        val second = postBank(a, a["alice"]).responseBody!!

        assertThat(questionIds(second)).contains(q)
        assertThat(questions(second)).isEqualTo(questions(first))
    }

    @Test
    fun `I1 미션 시작은 한 번 · 시작 시각 하나 — 같은 유저 100건 동시 × 20회`(): Unit = runBlocking {
        repeat(20) { round ->
            val g = group("alice" to "${D}T06:00:00+09:00", "bob" to "${D}T06:00:00+09:00")
            val token = access(g, g["alice"])

            val bodies = concurrently(100) { postBank(token) }

            assertThat(bodies.map { it.status.value() }).describedAs("round $round").containsOnly(200)
            assertThat(bodies.map { it.responseBody!!["missionStartedAt"] }.toSet()).describedAs("round $round").hasSize(1)
            assertThat(missionStarted(g, g["alice"])).describedAs("round $round").isEqualTo(1)
        }
    }

    @Test
    fun `I2 은행은 하나 — 은행 키 없는 유저 100건 동시 × 20회, 응답 모두 같고 키 값 = 응답`(): Unit = runBlocking {
        val rows = questionRows().values.associateBy { it.id }
        var raced = 0
        repeat(20) { round ->
            val g = group("alice" to "${D}T06:00:00+09:00", "bob" to "${D}T06:00:00+09:00")
            val token = access(g, g["alice"])

            val bodies = concurrently(100) { postBank(token) }

            val lists = bodies.map { questionIds(it.responseBody!!) }.toSet()
            assertThat(lists).describedAs("round $round").hasSize(1)
            val raw = redis.opsForValue().get(bankKey(g["alice"])).awaitSingle()
            val fromKey = Regex("\"[123]\":\\[([0-9,]*)]").findAll(raw).flatMap { m -> m.groupValues[1].split(',') }
                .map { rows.getValue(it.toLong()).pk }.toList()
            assertThat(fromKey).describedAs("round $round").isEqualTo(lists.single())
            // 은행 키가 없는 채로 Q2 까지 간 요청 수 − 1 = SET NX 에서 지고 다시 읽은 요청(LLD §3.2-3)
            raced += servedIds.calls(g["alice"].id) - 1
        }
        // 실제로 경합했다 — 둘 이상이 동시에 은행을 만들려 했는데도 응답은 하나였다
        assertThat(raced).isPositive()
    }

    @Test
    fun `I6 진행도는 줄지 않고 시작은 -1 에서만 — p 2 인 유저의 문제은행은 p · sa 그대로 · mission_started 0건`(): Unit = runBlocking {
        val a = groupA()
        postBank(a, a["bob"]) // 명단 · 그룹 Hash 를 만든다
        val uid = a["alice"].id.value
        val sa = micros(at(LocalTime.of(7, 0, 2)))
        redis.opsForHash<String, String>().putAll(
            redisKeys.group(a.gid, D),
            mapOf("$uid:p" to "2", "$uid:st" to "IN_PROGRESS", "$uid:sa" to sa.toString(), "$uid:late" to "0"),
        ).awaitSingleOrNull()
        val before = groupHash(a)

        val result = postBank(a, a["alice"])

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(micros(startedAt(result.responseBody!!))).isEqualTo(sa)
        assertThat(groupHash(a)).isEqualTo(before)
        assertThat(missionStarted(a, a["alice"])).isZero()
    }

    @Test
    fun `O7 (바탕) 이미 완료한 유저도 창 안이면 200 · 같은 은행(GS14)`(): Unit = runBlocking {
        val a = groupA()
        val first = postBank(a, a["alice"]).responseBody!!
        val uid = a["alice"].id.value
        redis.opsForHash<String, String>().putAll(redisKeys.group(a.gid, D), mapOf("$uid:p" to "3", "$uid:st" to "SUCCEEDED"))
            .awaitSingleOrNull()

        val again = postBank(a, a["alice"])

        assertThat(again.status.value()).isEqualTo(200)
        assertThat(questions(again.responseBody!!)).isEqualTo(questions(first))
        assertThat(again.responseBody!!["missionStartedAt"]).isEqualTo(first["missionStartedAt"])
    }

    /** [n] 건을 동시에 — WebTestClient 호출은 스레드를 막으므로 [n] 개 스레드에 나눠 띄운다. */
    private suspend fun <T> concurrently(n: Int, call: () -> T): List<T> = coroutineScope {
        val io = Dispatchers.IO.limitedParallelism(n)
        (1..n).map { async(io) { call() } }.awaitAll()
    }
}
