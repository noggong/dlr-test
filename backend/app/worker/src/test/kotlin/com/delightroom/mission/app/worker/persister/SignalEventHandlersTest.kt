package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.app.worker.WorkerTestContainers
import com.delightroom.mission.app.worker.persister.SignalFixture.Companion.usToInstant
import com.delightroom.mission.app.worker.persister.SignalFixture.Member
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * solve-signal 의 persister 처리기(ORACLE solve-signal O17 의 DB 쪽 · O30 ~ O33 · LLD §2.3 · §3.6).
 *
 * 「같은 메시지 2번 처리」는 처리기를 직접 두 번 부르고 두 번째 뒤의 행 전부(`updated_at` 포함)를 첫 번째 뒤와 비교한다.
 * O33 은 스프링이 띄운 소비 루프에 Stream 으로 넣는다. 스키마는 `spring.sql.init`(schema.sql · data.sql 문제 시드)이 적용한다.
 */
@SpringBootTest(
    properties = [
        "game.bucket-count=8",
        "spring.sql.init.mode=always",
        "worker.persister.idle-delay=20ms",
        "worker.persister.retry-backoff=100ms",
        "worker.persister.retry-backoff-max=500ms",
    ],
)
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
@DirtiesContext
class SignalEventHandlersTest
    @Autowired
    constructor(
        private val handlers: SignalEventHandlers,
        private val persister: StreamPersister,
        private val db: DatabaseClient,
        private val tx: TransactionalOperator,
        redis: ReactiveStringRedisTemplate,
    ) {
        private val fx = SignalFixture(db, redis, bucketCount = 8)

        @BeforeEach
        fun running() {
            assertThat(persister.isRunning).isTrue()
        }

        /** 처리기를 두 번 부르고, 첫 번째 뒤와 두 번째 뒤의 [tables] 행이 같은지 본다. 첫 번째 뒤의 행을 돌려준다. */
        private suspend fun twice(g: SignalGroup, fields: Map<String, String>, vararg tables: String): Map<String, List<Map<String, Any?>>> {
            handlers.handle(fx.event(fields))
            val first = tables.associateWith { fx.rows(it, g) }
            handlers.handle(fx.event(fields))
            tables.forEach { assertThat(fx.rows(it, g)).describedAs("두 번째 처리 뒤 $it").isEqualTo(first.getValue(it)) }
            return first
        }

        private fun instant(v: Any?): Instant? = (v as OffsetDateTime?)?.toInstant()

        private fun int(v: Any?): Int = (v as Number).toInt()

        @Test
        fun `O30 solve_applied 를 두 번 처리해도 행이 같다 — 원장 1행 · 진행도 · 마지막 풀이 시각 · SOLVED`(): Unit = runBlocking {
            val g = fx.group(2)
            val uid = g.uids[0]
            val solve = fx.solve(g, uid, 1)
            val rows = twice(g, solve, "solve_signals", "daily_progress", "served_questions")

            val signals = rows.getValue("solve_signals")
            assertThat(signals).hasSize(1)
            assertThat(int(signals[0]["question_no"])).isEqualTo(1)
            assertThat((signals[0]["question_id"] as Number).toLong()).isEqualTo(g.questions.getValue(1).first)
            val p = rows.getValue("daily_progress").single { (it["user_id"] as Number).toLong() == uid }
            assertThat(int(p["progress"])).isEqualTo(solve.getValue("progress").toInt())
            assertThat(instant(p["last_solved_at"])).isEqualTo(usToInstant(solve.getValue("arrivedAt").toLong()))
            assertThat(p["status"]).isEqualTo("IN_PROGRESS")
            val served = rows.getValue("served_questions").filter { (it["user_id"] as Number).toLong() == uid }
                .associate { (it["question_id"] as Number).toLong() to it["kind"] }
            assertThat(served[g.questions.getValue(1).first]).isEqualTo("SOLVED")
            assertThat(served[g.questions.getValue(2).first]).isEqualTo("SHOWN")

            // 2 · 3 번까지 — 3 번 반영에서 SUCCEEDED, 늦게 온 1 번 재처리는 진행도 · 시각을 되돌리지 않는다
            fx.solve(g, uid, 2).let { handlers.handle(fx.event(it)) }
            val third = fx.solve(g, uid, 3)
            twice(g, third, "solve_signals", "daily_progress", "served_questions")
            handlers.handle(fx.event(solve))
            val done = fx.progress(g, uid)
            assertThat(int(done["progress"])).isEqualTo(3)
            assertThat(done["status"]).isEqualTo("SUCCEEDED")
            assertThat(instant(done["last_solved_at"])).isEqualTo(usToInstant(third.getValue("arrivedAt").toLong()))
            assertThat(fx.rows("solve_signals", g)).hasSize(3)
        }

        @Test
        fun `O17 solve_signals 의 success_time 은 보낸 값 · arrived_at 은 Lua 시각 t`(): Unit = runBlocking {
            val g = fx.group(2)
            val past = fx.solve(g, g.uids[0], 1, successTime = "2030-01-15T06:00:00+09:00")
            val future = fx.solve(g, g.uids[1], 1, successTime = "2099-12-31T23:59:59.999+09:00")
            handlers.handle(fx.event(past))
            handlers.handle(fx.event(future))

            val byUser = fx.rows("solve_signals", g).associateBy { (it["user_id"] as Number).toLong() }
            for ((uid, sent) in listOf(g.uids[0] to past, g.uids[1] to future)) {
                val row = byUser.getValue(uid)
                assertThat(instant(row["success_time"])).isEqualTo(OffsetDateTime.parse(sent.getValue("successTime")).toInstant())
                assertThat(instant(row["arrived_at"])).isEqualTo(usToInstant(sent.getValue("at").toLong()))
                assertThat(instant(row["click_time"])).isEqualTo(usToInstant(sent.getValue("clickedAt").toLong()))
                // 랭킹 키(last_solved_at)도 successTime 이 아니라 t
                assertThat(instant(fx.progress(g, uid)["last_solved_at"])).isEqualTo(usToInstant(sent.getValue("at").toLong()))
            }
        }

        @Test
        fun `O31 pass_used 를 두 번 처리해도 행이 같다 — PASSED 1행 · 패스 번호 · 넘긴 문제`(): Unit = runBlocking {
            val g = fx.group(2)
            val uid = g.uids[0]
            val pass = fx.pass(g, uid, 2)
            val rows = twice(g, pass, "daily_progress", "served_questions")

            val q2 = g.questions.getValue(2).first
            val served = rows.getValue("served_questions").filter { (it["user_id"] as Number).toLong() == uid && (it["question_id"] as Number).toLong() == q2 }
            assertThat(served).hasSize(1)
            assertThat(served[0]["kind"]).isEqualTo("PASSED")
            val p = rows.getValue("daily_progress").single { (it["user_id"] as Number).toLong() == uid }
            assertThat(int(p["pass_question_no"])).isEqualTo(2)
            assertThat((p["passed_question_id"] as Number).toLong()).isEqualTo(q2)

            // 진입 신호 기록(SHOWN)이 아직 없는 문제도 PASSED 로 새로 넣는다 · 두 번째 패스는 번호를 바꾸지 않는다
            val other = g.uids[1]
            db.sql("DELETE FROM served_questions WHERE user_id = :u").bind("u", other).then().awaitFirstOrNull()
            handlers.handle(fx.event(fx.pass(g, other, 1)))
            handlers.handle(fx.event(fx.pass(g, other, 3)))
            val otherServed = fx.rows("served_questions", g).filter { (it["user_id"] as Number).toLong() == other }
            assertThat(otherServed.map { it["kind"] }).containsOnly("PASSED")
            assertThat(int(otherServed.single { (it["question_id"] as Number).toLong() == g.questions.getValue(1).first }["question_no"])).isEqualTo(1)
            assertThat(int(fx.progress(g, other)["pass_question_no"])).isEqualTo(1)
        }

        @Test
        fun `O31 member_cheat_failed 를 두 번 처리해도 행이 같다 — CHEAT_FAILED · cheat_failed_at = at`(): Unit = runBlocking {
            val g = fx.group(2)
            val uid = g.uids[0]
            val fail = fx.cheatFail(g, uid, progress = 0)
            val rows = twice(g, fail, "daily_progress")
            val p = rows.getValue("daily_progress").single { (it["user_id"] as Number).toLong() == uid }
            assertThat(p["status"]).isEqualTo("CHEAT_FAILED")
            assertThat(instant(p["cheat_failed_at"])).isEqualTo(usToInstant(fail.getValue("at").toLong()))

            // 미션 성공 뒤의 부정 실패는 반영하지 않는다(D9 — WHERE status = 'IN_PROGRESS')
            val succeeded = g.uids[1]
            fx.setStatus(g, succeeded, "SUCCEEDED", 3)
            handlers.handle(fx.event(fx.cheatFail(g, succeeded, progress = 3)))
            assertThat(fx.progress(g, succeeded)["status"]).isEqualTo("SUCCEEDED")
            assertThat(fx.progress(g, succeeded)["cheat_failed_at"]).isNull()
        }

        @Test
        fun `O32 group_decided 를 두 번 처리해도 group_results 1행 · 멤버 행 = memberCount`(): Unit = runBlocking {
            val g = fx.group(3)
            val (a, b, c) = g.uids
            val members = listOf(
                Member(a, "SUCCEEDED", 3, lastSolvedUs = fx.nextUs(), passNo = 2),
                Member(b, "SUCCEEDED", 3, lastSolvedUs = fx.nextUs()),
                Member(c, "CHEAT_FAILED", 1, lastSolvedUs = fx.nextUs()),
            )
            val decided = fx.decided(g, "FAILED_IMMEDIATE", members)
            val rows = twice(g, decided, "group_results", "group_result_members", "daily_progress")

            val result = rows.getValue("group_results").single()
            assertThat(result["result"]).isEqualTo("FAILED_IMMEDIATE")
            assertThat(int(result["member_count"])).isEqualTo(3)
            assertThat(instant(result["completed_at"])).isEqualTo(usToInstant(decided.getValue("decidedAt").toLong()))
            val byUser = rows.getValue("group_result_members").associateBy { (it["user_id"] as Number).toLong() }
            assertThat(byUser).hasSize(decided.getValue("memberCount").toInt())
            assertThat(byUser.mapValues { int(it.value["rank"]) }).isEqualTo(mapOf(a to 1, b to 2, c to 3))
            assertThat(int(byUser.getValue(a)["pass_question_no"])).isEqualTo(2)
            assertThat(byUser.getValue(b)["pass_question_no"]).isNull()
            assertThat(instant(byUser.getValue(c)["last_solved_at"])).isEqualTo(usToInstant(members[2].lastSolvedUs!!))
            assertThat(byUser.mapValues { it.value["cheat_failed"] }).isEqualTo(mapOf(a to false, b to false, c to true))
            assertThat(byUser.values.map { it["started"] }).containsOnly(true)
            // 즉시 실패 결과는 daily_progress 를 바꾸지 않는다(마감 실패만 — LLD platform §3)
            assertThat(rows.getValue("daily_progress").map { it["status"] }).containsOnly("IN_PROGRESS")

            // 같은 그룹 · 날짜의 다른 결과가 와도 덮지 않는다(정확히 한 번 — NFR-2)
            handlers.handle(fx.event(fx.decided(g, "SUCCEEDED", members.reversed())))
            assertThat(fx.rows("group_results", g)).isEqualTo(rows.getValue("group_results"))
            assertThat(fx.rows("group_result_members", g)).isEqualTo(rows.getValue("group_result_members"))
        }

        @Test
        fun `group_decided FAILED_DEADLINE 은 같은 트랜잭션에서 미완료 그룹원을 FAILED 로 남긴다`(): Unit = runBlocking {
            val g = fx.group(4)
            val (done, cheat, going, absent) = g.uids
            fx.setStatus(g, done, "SUCCEEDED", 3)
            fx.setStatus(g, cheat, "CHEAT_FAILED", 1)
            fx.setStatus(g, going, "IN_PROGRESS", 2)
            fx.setStatus(g, absent, "NOT_STARTED", -1)
            val decided = fx.decided(
                g, "FAILED_DEADLINE",
                listOf(
                    Member(done, "SUCCEEDED", 3, fx.nextUs()),
                    Member(going, "FAILED", 2, fx.nextUs()),
                    Member(cheat, "CHEAT_FAILED", 1, fx.nextUs()),
                    Member(absent, "FAILED", -1),
                ),
                rankingFinal = 0,
            )
            val rows = twice(g, decided, "group_results", "group_result_members", "daily_progress")

            val status = rows.getValue("daily_progress").associate { (it["user_id"] as Number).toLong() to it["status"] }
            assertThat(status).isEqualTo(mapOf(done to "SUCCEEDED", cheat to "CHEAT_FAILED", going to "FAILED", absent to "FAILED"))
            val members = rows.getValue("group_result_members").associateBy { (it["user_id"] as Number).toLong() }
            assertThat(members).hasSize(4)
            assertThat(members.getValue(absent)["started"]).isEqualTo(false)
            assertThat(members.getValue(absent)["last_solved_at"]).isNull()
            assertThat(int(members.getValue(absent)["progress"])).isEqualTo(-1)
        }

        @Test
        fun `칸이 틀린 메시지는 MalformedEventException — 행을 쓰지 않는다`(): Unit = runBlocking {
            val g = fx.group(2)
            val good = fx.solve(g, g.uids[0], 1)
            val broken = listOf(
                good - "arrivedAt",
                good + ("arrivedAt" to "1.7e15"),
                good + ("questionNo" to "4"),
                good + ("questionId" to "XYZ"),
                good + ("successTime" to "2030-01-15T07:03:10"),
                good + ("date" to "2030/01/15"),
                fx.pass(g, g.uids[0], 1) + ("uid" to ""),
                fx.cheatFail(g, g.uids[0], 0) - "cheatFailedAt",
                fx.decided(g, "SUCCEEDED", listOf(Member(g.uids[0], "SUCCEEDED", 3, fx.nextUs()))) + ("memberCount" to "2"),
                fx.decided(g, "SUCCEEDED", listOf(Member(g.uids[0], "SUCCEEDED", 3, fx.nextUs()))) + ("ranking" to "{}"),
                fx.decided(g, "PENDING", listOf(Member(g.uids[0], "SUCCEEDED", 3, fx.nextUs()))),
                fx.decided(g, "SUCCEEDED", listOf(Member(g.uids[0], "DONE", 3, fx.nextUs()))),
            )
            for (fields in broken) {
                assertThatThrownBy { runBlocking { handlers.handle(fx.event(fields)) } }
                    .describedAs("$fields").isInstanceOf(MalformedEventException::class.java)
            }
            assertThat(fx.rows("solve_signals", g)).isEmpty()
            assertThat(fx.rows("group_results", g)).isEmpty()
            assertThat(fx.rows("daily_progress", g).map { it["status"] }).containsOnly("IN_PROGRESS")
        }

        @Test
        fun `O33 소비 루프가 그룹의 메시지를 Stream 순서대로 기록하고 XPENDING 0`(): Unit = runBlocking {
            val g = fx.group(2)
            val (a, b) = g.uids
            val events = listOf(
                fx.solve(g, a, 1), fx.pass(g, b, 1), fx.solve(g, a, 2), fx.solve(g, a, 3), fx.cheatFail(g, b, progress = 0),
            )
            events.forEach { fx.xadd(g, it) }
            val solved3 = events[3].getValue("at").toLong()
            fx.xadd(g, fx.decided(g, "FAILED_IMMEDIATE", listOf(Member(a, "SUCCEEDED", 3, solved3), Member(b, "CHEAT_FAILED", 0, passNo = 1))))

            eventually(what = "group_results 1행") { fx.rows("group_results", g).size == 1 }
            eventually(what = "pending 0") { fx.pending(g) == 0L }
            assertThat(fx.progress(g, a)["status"]).isEqualTo("SUCCEEDED")
            assertThat(fx.progress(g, b)["status"]).isEqualTo("CHEAT_FAILED")
            assertThat(int(fx.progress(g, b)["pass_question_no"])).isEqualTo(1)
            assertThat(fx.rows("solve_signals", g)).hasSize(3)
            assertThat(fx.rows("group_result_members", g)).hasSize(2)
        }

        @Test
        fun `O33 처리 중 DB 연결이 끊기면 ACK 하지 않고 같은 메시지를 다시 처리한다`(): Unit = runBlocking {
            val g = fx.group(2)
            val solve = fx.solve(g, g.uids[0], 1)
            val release = CompletableDeferred<Unit>()
            val locked = CompletableDeferred<Unit>()
            // 다른 연결이 solve_signals 쓰기를 잠가(읽기는 된다) 처리기 트랜잭션을 INSERT 에서 기다리게 한다
            val locker = async {
                tx.executeAndAwait {
                    db.sql("LOCK TABLE solve_signals IN EXCLUSIVE MODE").then().awaitFirstOrNull()
                    locked.complete(Unit)
                    release.await()
                }
            }
            locked.await()
            fx.xadd(g, solve)

            // 잠금을 기다리는 연결(= 처리 중인 처리기의 트랜잭션)을 끊는다
            var killed = 0L
            eventually(what = "처리기 연결 끊기") {
                killed = db.sql(
                    "SELECT count(pg_terminate_backend(pid)) AS n FROM pg_stat_activity " +
                        "WHERE wait_event_type = 'Lock' AND datname = current_database() AND pid <> pg_backend_pid()",
                ).map { row -> row.get("n", Number::class.java)!!.toLong() }.one().awaitSingle()
                killed > 0
            }
            assertThat(fx.pending(g)).describedAs("커밋 안 됨 → ACK 안 함").isEqualTo(1L)
            assertThat(fx.rows("solve_signals", g)).isEmpty()
            release.complete(Unit)
            locker.await()

            eventually(timeout = Duration.ofSeconds(60), what = "재처리 · pending 0") {
                fx.rows("solve_signals", g).size == 1 && fx.pending(g) == 0L
            }
            val p = fx.progress(g, g.uids[0])
            assertThat(int(p["progress"])).isEqualTo(1)
            assertThat(instant(p["last_solved_at"])).isEqualTo(usToInstant(solve.getValue("arrivedAt").toLong()))
            assertThat(fx.rows("served_questions", g).single { (it["question_id"] as Number).toLong() == g.questions.getValue(1).first && (it["user_id"] as Number).toLong() == g.uids[0] }["kind"])
                .isEqualTo("SOLVED")
        }
    }
