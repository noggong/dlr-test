package com.delightroom.mission.core.gamesession

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.board.MemberStatus
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.progress.ShownOutcome
import com.delightroom.mission.core.domain.question.Category
import com.delightroom.mission.core.domain.question.Difficulty
import com.delightroom.mission.core.domain.question.Question
import com.delightroom.mission.core.domain.question.QuestionCatalog
import com.delightroom.mission.core.domain.question.QuestionId
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.DailyProgressStore
import com.delightroom.mission.core.port.GroupRepository
import com.delightroom.mission.core.port.ProgressRepository
import com.delightroom.mission.core.port.QuestionBankCache
import com.delightroom.mission.core.port.QuestionRepository
import com.delightroom.mission.core.port.RosterRepository
import com.delightroom.mission.core.port.UserRepository
import com.delightroom.mission.core.service.GameService
import com.delightroom.mission.core.service.RosterService
import com.delightroom.mission.core.testfixture.MutableClock
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * `GameService.markShown` 의 앱 쪽 몫(LLD §3.3-2 · 3) — 문제 캐시로 본 `qok`(내부 ID 또는 null)를 Lua 에 넘기고 직접 거부하지 않는다.
 * Lua 판정 · 고정은 Redis(ShownScriptTest) · API(ShownTest) 테스트가 본다. 저장소는 부른 인자를 적는 가짜(Proxy)다.
 */
class GameServiceShownTest {
    private val properties = GameProperties()
    private val calls = mutableListOf<String>()
    private val internalIds = mutableListOf<Long?>()

    private inline fun <reified T : Any> fake(noinline answer: (String, Array<Any?>) -> Any? = { n, _ -> error("안 부른다: $n") }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> 0
                else -> {
                    val name = method.name.substringBefore('-')
                    calls += "${T::class.simpleName}.$name"
                    answer(name, args ?: emptyArray())
                }
            }
        } as T

    private fun service(appNow: String, reply: ShownOutcome): GameService {
        val clock = MutableClock(OffsetDateTime.parse(appNow).toInstant(), properties.zone)
        val gameClock = GameClock(properties, clock)
        val progresses = fake<ProgressRepository> { name, args ->
            check(name == "markShown") { "안 부른다: $name" }
            // (uid, gid, date, questionNo, questionId, internalId, continuation) — value class 는 펼쳐져 온다
            internalIds += args[5] as Long?
            reply
        }
        val rosters = RosterService(fake<RosterRepository>(), fake<UserRepository>(), fake<GroupRepository>(), fake<DailyProgressStore>(), gameClock)
        return GameService(
            fake<UserRepository>(), fake<GroupRepository>(), fake<QuestionRepository>(), progresses, properties,
            clock, gameClock, rosters, fake<QuestionBankCache>(), CATALOG,
        )
    }

    @Test
    fun `O22 (바탕) 있는 PK 이고 난이도 = 번호면 내부 ID, 없는 PK · 난이도 다름이면 null 로 Lua 에 넘긴다`(): Unit = runBlocking {
        val ok = ShownOutcome.Shown(0, MemberStatus.IN_PROGRESS, created = true)
        val shown = service("${D}T07:10:00+09:00", ok).markShown(UID, GID, 1, Q1)
        service("${D}T07:10:00+09:00", ok).markShown(UID, GID, 2, Q1) // 난이도 1 문제로 2번
        service("${D}T07:10:00+09:00", ok).markShown(UID, GID, 1, QuestionId("f".repeat(64)))

        assertThat(internalIds).containsExactly(101L, null, null)
        assertThat(shown.questionNo).isEqualTo(1)
        assertThat(shown.questionId).isEqualTo(Q1)
        assertThat(shown.progress).isZero()
        assertThat(shown.missionStatus).isEqualTo(MemberStatus.IN_PROGRESS)
    }

    @Test
    fun `O24 (바탕) Lua 거부 코드는 그대로 오류 코드 · NO_ROSTER 는 07시 전 앱 시각이면 명단을 만들지 않고 GAME_NOT_OPEN`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service("${D}T07:10:00+09:00", ShownOutcome.Rejected(ErrorCode.MISSION_NOT_STARTED)).markShown(UID, GID, 1, Q1) } }
            .extracting { (it as BusinessException).errorCode }.isEqualTo(ErrorCode.MISSION_NOT_STARTED)
        calls.clear()

        assertThatThrownBy { runBlocking { service("${D}T06:59:59.999+09:00", ShownOutcome.NoRoster).markShown(UID, GID, 1, Q1) } }
            .extracting { (it as BusinessException).errorCode }.isEqualTo(ErrorCode.GAME_NOT_OPEN)
        assertThat(calls).containsExactly("ProgressRepository.markShown")
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val UID = UserId(11)
        val GID = GroupId(7)
        val Q1 = QuestionId("a".repeat(64))
        val CATALOG = QuestionCatalog(
            listOf(
                Question(101, Q1, Difficulty.ONE, Category.GENERAL, "본문", "0".repeat(64)),
                Question(201, QuestionId("c".repeat(64)), Difficulty.TWO, Category.GENERAL, "본문", "1".repeat(64)),
            ),
        )
    }
}
