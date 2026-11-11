package com.delightroom.mission.app.api.gamesession

import com.delightroom.mission.app.api.auth.AuthApiTestSupport
import com.delightroom.mission.app.api.auth.AuthCookies
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.TokenIssuer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Range
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 문제은행 · 진입 신호 API(`POST /api/v1/games/today/question-bank` · `…/shown`) 테스트의 바탕 — ORACLE game-session O5 ~ O24 · I1 ~ I3 · I6.
 *
 * 시각: 판정은 Lua 가 Redis `TIME` + `game.clock-offset` 으로 하므로 시계(`MutableClock`)는 실제 지금에 두고, 하위 클래스가
 * `@DynamicPropertySource` 로 오프셋을 [D] 의 어느 KST 시각에 맞춘다([offsetTo]). 그 시각부터 테스트가 도는 만큼 흐른다.
 * 고정 데이터(ORACLE §0)의 그룹 · 유저는 매번 새로 넣는다(테스트끼리 DB · Redis 를 비우지 않는다). 문제는 시드(난이도마다 120개)다.
 * Q2(출제 이력) 호출은 [ServedIdsCounter] 가 유저마다 센다(O14 의 「리포지토리 스파이」).
 */
@Import(QuestionBankApiTestSupport.ServedIdsCounter::class, QuestionBankApiTestSupport.ShownCallCounter::class)
abstract class QuestionBankApiTestSupport : AuthApiTestSupport() {
    @Autowired
    protected lateinit var tokenIssuer: TokenIssuer

    @Autowired
    protected lateinit var gameProperties: GameProperties

    @Autowired
    protected lateinit var servedIds: ServedIdsCounter

    @Autowired
    protected lateinit var shownCalls: ShownCallCounter

    protected class Member(val id: UserId, val loginId: String)

    protected class Fixture(val gid: GroupId, val name: String, val members: Map<String, Member>) {
        operator fun get(prefix: String): Member = members.getValue(prefix)
    }

    /** `findServedIds(userId)`(Q2) 를 유저마다 센다 — 나머지는 그대로 넘긴다. */
    class ServedIdsCounter : BeanPostProcessor {
        private val calls = ConcurrentHashMap<UserId, AtomicInteger>()

        fun calls(userId: UserId): Int = calls[userId]?.get() ?: 0

        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is QuestionRepository) Counting(bean) else bean

        private inner class Counting(private val delegate: QuestionRepository) : QuestionRepository by delegate {
            override suspend fun findServedIds(userId: UserId): Set<Long> {
                calls.computeIfAbsent(userId) { AtomicInteger() }.incrementAndGet()
                return delegate.findServedIds(userId)
            }
        }
    }

    /** `ProgressRepository.markShown`(Lua `shown`) 호출을 유저마다 센다 — O19 「Lua 호출 0」. 나머지는 그대로 넘긴다. */
    class ShownCallCounter : BeanPostProcessor {
        private val calls = ConcurrentHashMap<UserId, AtomicInteger>()

        fun calls(userId: UserId): Int = calls[userId]?.get() ?: 0

        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is ProgressRepository) Counting(bean) else bean

        private inner class Counting(private val delegate: ProgressRepository) : ProgressRepository by delegate {
            override suspend fun markShown(
                userId: UserId,
                groupId: GroupId,
                date: LocalDate,
                questionNo: Int,
                questionId: QuestionId,
                internalId: Long?,
            ): ShownOutcome {
                calls.computeIfAbsent(userId) { AtomicInteger() }.incrementAndGet()
                return delegate.markShown(userId, groupId, date, questionNo, questionId, internalId)
            }
        }
    }

    @BeforeEach
    fun realNowAndPartitions() = runBlocking {
        clock.set(Instant.now())
        // 하루 파티션은 기동 때 KST 오늘 · 내일만 생긴다 — 고정 날짜 D 는 직접 만든다(명단을 만들 때 Q10 -1 행)
        for (d in listOf(D, D.plusDays(1))) {
            val suffix = d.toString().replace("-", "")
            db.sql(
                "CREATE TABLE IF NOT EXISTS daily_progress_$suffix PARTITION OF daily_progress " +
                    "FOR VALUES FROM ('$d') TO ('${d.plusDays(1)}')",
            ).then().awaitSingleOrNull()
        }
    }

    /** 그룹 하나와 그룹원 — (아이디 앞부분, 가입 시각). 가입 API 는 `joined_at = now()` 라 행을 직접 넣는다. */
    protected fun group(vararg joins: Pair<String, String>): Fixture = runBlocking {
        val tag = "${RUN}${SEQ.incrementAndGet()}"
        val name = "Bank-$tag"
        val gid = db.sql("INSERT INTO groups (name, name_key, member_count) VALUES (:n, lower(:n), :c) RETURNING id")
            .bind("n", name).bind("c", joins.size)
            .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle()
        val members = joins.associate { (prefix, joinedAt) ->
            val loginId = "${prefix}_$tag"
            val id = db.sql(
                "INSERT INTO users (login_id, password_hash, group_id, joined_at) VALUES (:l, 'x', :g, :j) RETURNING id",
            )
                .bind("l", loginId).bind("g", gid).bind("j", OffsetDateTime.parse(joinedAt))
                .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle()
            prefix to Member(UserId(id), loginId)
        }
        Fixture(GroupId(gid), name, members)
    }

    /** 고정 데이터 그룹 A — 06:00 가입 alice · bob · carol + 07:30 가입 late. */
    protected fun groupA() = group(
        "alice" to "${D}T06:00:00+09:00",
        "bob" to "${D}T06:00:00+09:00",
        "carol" to "${D}T06:00:00+09:00",
        "late" to "${D}T07:30:00+09:00",
    )

    protected fun access(fixture: Fixture, member: Member): String =
        tokenIssuer.issue(
            Account(member.id, LoginId(member.loginId), "", fixture.gid, GroupName(fixture.name)),
            "jti-${member.loginId}",
            gameProperties.now(clock),
        ).access

    protected fun postBank(fixture: Fixture, member: Member): EntityExchangeResult<Map<String, Any>> =
        postBank(access(fixture, member))

    protected fun postBank(access: String): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(QUESTION_BANK).cookie(AuthCookies.ACCESS, access)
            .exchange().expectBody(MAP_TYPE).returnResult()

    /** 진입 신호(`POST /shown`) — 본문 `{questionNo, questionId}`. */
    protected fun postShown(fixture: Fixture, member: Member, questionNo: Int, questionId: String): EntityExchangeResult<Map<String, Any>> =
        postShownRaw(access(fixture, member), body(questionNo, questionId))

    /** 진입 신호 — 본문 글자 그대로(검증 테스트 · 동시 요청). */
    protected fun postShownRaw(access: String, body: String): EntityExchangeResult<Map<String, Any>> =
        client.post().uri(SHOWN).cookie(AuthCookies.ACCESS, access).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectBody(MAP_TYPE).returnResult()

    protected fun body(questionNo: Int, questionId: String): String = """{"questionNo":$questionNo,"questionId":"$questionId"}"""

    @Suppress("UNCHECKED_CAST")
    protected fun questions(body: Map<String, Any>): List<Map<String, Any>> = body["questions"] as List<Map<String, Any>>

    protected fun questionIds(body: Map<String, Any>): List<String> = questions(body).map { it["questionId"] as String }

    protected fun startedAt(body: Map<String, Any>): Instant = OffsetDateTime.parse(body["missionStartedAt"] as String).toInstant()

    protected fun groupHash(fixture: Fixture): Map<String, String> = runBlocking {
        redis.opsForHash<String, String>().entries(redisKeys.group(fixture.gid, D)).collectMap({ it.key }, { it.value }).awaitSingle()
    }

    protected fun hasKey(key: String): Boolean = runBlocking { redis.hasKey(key).awaitSingle() }

    protected fun bankKey(member: Member): String = redisKeys.bank(member.id, D)

    /** 이 그룹의 이벤트(버킷 Stream 에서 gid 로 거른다) — 오래된 것부터. */
    protected fun events(fixture: Fixture): List<Map<String, String>> = runBlocking {
        redis.opsForStream<String, String>().range(redisKeys.events(fixture.gid), Range.unbounded()).collectList().awaitSingle()
            .map { r -> r.value.entries.associate { it.key.toString() to it.value.toString() } }
            .filter { it["gid"] == fixture.gid.value.toString() }
    }

    protected fun missionStarted(fixture: Fixture, member: Member): Int =
        events(fixture).count { it["type"] == "mission.started" && it["uid"] == member.id.value.toString() }

    /** 문제 행(id → pk_hash · difficulty · answer_hash). */
    protected fun questionRows(): Map<Long, QuestionRow> = runBlocking {
        db.sql("SELECT id, pk_hash, difficulty, answer_hash FROM questions")
            .map { r ->
                QuestionRow(
                    r.get("id", Number::class.java)!!.toLong(),
                    r.get("pk_hash", String::class.java)!!,
                    r.get("difficulty", Number::class.java)!!.toInt(),
                    r.get("answer_hash", String::class.java)!!,
                )
            }
            .all().collectList().awaitSingle().associateBy { it.id }
    }

    protected data class QuestionRow(val id: Long, val pk: String, val difficulty: Int, val answerHash: String)

    /** `served_questions` 에 (유저, 문제들) — 진입 신호를 persister 가 옮긴 것과 같은 행. */
    protected fun serve(member: Member, questionIds: Collection<Long>, date: LocalDate = D.minusDays(1), no: Int = 1) = runBlocking {
        for (qid in questionIds) {
            db.sql(
                "INSERT INTO served_questions (user_id, question_id, game_date, question_no, kind, served_at) " +
                    "VALUES (:u, :q, :d, :n, 'SHOWN', now()) ON CONFLICT DO NOTHING",
            ).bind("u", member.id.value).bind("q", qid).bind("d", date).bind("n", no).then().awaitSingleOrNull()
        }
    }

    companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val QUESTION_BANK = "/api/v1/games/today/question-bank"
        const val SHOWN = "/api/v1/games/today/shown"

        private val SEQ = AtomicInteger()
        private val RUN: String = Math.floorMod(System.nanoTime(), 46_656L).toString(36).padStart(3, '0')

        fun at(time: LocalTime): Instant = D.atTime(time).atZone(ZONE).toInstant()

        fun micros(i: Instant): Long = i.epochSecond * 1_000_000L + i.nano / 1_000L

        /** `game.clock-offset` — 지금을 [D] 의 [time] 으로. 컨텍스트를 만들 때 한 번 계산한다. */
        fun offsetTo(registry: DynamicPropertyRegistry, time: LocalTime) {
            registry.add("game.clock-offset") { Duration.between(Instant.now(), at(time)).toString() }
        }
    }
}
