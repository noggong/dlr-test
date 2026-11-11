package com.delightroom.mission.core.gamesession

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.config.GameProperties
import com.delightroom.mission.core.domain.game.GameClock
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.progress.MissionStartOutcome
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
 * `GameService.startMission` 의 앱 쪽 분기(LLD v6 §3.2-2) — Lua 판정 · 은행은 Redis · API 테스트가 본다.
 * 저장소는 부른 메서드 이름만 적는 가짜(Proxy)다 — 병렬 일감이 포트에 메서드를 더해도 이 테스트는 그대로 돈다.
 */
class GameServiceStartTest {
    private val properties = GameProperties()
    private val calls = mutableListOf<String>()

    /** [T] 의 가짜 — 부른 메서드를 `<타입>.<이름>` 으로 적고 [answer] 가 주는 값을 돌려준다(없으면 실패). */
    private inline fun <reified T : Any> fake(noinline answer: (String) -> Any? = { error("안 부른다: $it") }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> 0
                else -> {
                    // value class 인자를 받는 메서드는 이름 뒤에 `-<해시>` 가 붙는다(Kotlin 맹글링) — 떼고 적는다
                    val name = method.name.substringBefore('-')
                    calls += "${T::class.simpleName}.$name"
                    answer(name)
                }
            }
        } as T

    private fun service(appNow: String, start: (Int) -> MissionStartOutcome): GameService {
        val gameClock = GameClock(properties, MutableClock(OffsetDateTime.parse(appNow).toInstant(), properties.zone))
        var n = 0
        val progresses = fake<ProgressRepository> { name ->
            check(name == "startMission") { "안 부른다: $name" }
            start(++n)
        }
        val rosters = RosterService(fake<RosterRepository>(), fake<UserRepository>(), fake<GroupRepository>(), fake<DailyProgressStore>(), gameClock)
        return GameService(
            fake<UserRepository>(), fake<GroupRepository>(), fake<QuestionRepository>(), progresses, properties,
            MutableClock(OffsetDateTime.parse(appNow).toInstant(), properties.zone), gameClock, rosters, fake<QuestionBankCache>(), null,
        )
    }

    @Test
    fun `O8 (바탕) Lua 는 07시를 넘겼는데 앱 시각이 아직 07시 전이면(시계 차이) 명단을 만들지 않고 GAME_NOT_OPEN`(): Unit = runBlocking {
        val service = service("${D}T06:59:59.999+09:00") { MissionStartOutcome.NoRoster }

        assertThatThrownBy { runBlocking { service.startMission(UID, GID) } }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }.isEqualTo(ErrorCode.GAME_NOT_OPEN)
        assertThat(calls).containsExactly("ProgressRepository.startMission")
    }

    @Test
    fun `O10 (바탕) Lua 거부 코드는 그대로 오류 코드 — 명단 · 은행을 건드리지 않는다`(): Unit = runBlocking {
        for (code in listOf(ErrorCode.GAME_NOT_OPEN, ErrorCode.SUBMISSION_CLOSED, ErrorCode.NOT_PARTICIPANT, ErrorCode.SOLO_GROUP)) {
            calls.clear()
            val service = service("${D}T07:10:00+09:00") { MissionStartOutcome.Rejected(code) }

            assertThatThrownBy { runBlocking { service.startMission(UID, GID) } }
                .extracting { (it as BusinessException).errorCode }.isEqualTo(code)
            assertThat(calls).describedAs("$code").containsExactly("ProgressRepository.startMission")
        }
    }

    @Test
    fun `O5 (바탕) 명단을 만든 뒤에도 NO_ROSTER 면 한 번만 다시 부르고 끝낸다(500 — 일어나지 않아야 한다)`(): Unit = runBlocking {
        // 명단 저장소는 「이미 있다」로 답한다 — findOrCreate 가 곧바로 돌려준다
        val gameClock = GameClock(properties, MutableClock(OffsetDateTime.parse("${D}T07:10:00+09:00").toInstant(), properties.zone))
        val roster = com.delightroom.mission.core.domain.roster.DayRoster(
            GID, D, com.delightroom.mission.core.domain.group.GroupName("g"), emptyMap(),
        )
        val rosters = RosterService(fake<RosterRepository> { roster }, fake<UserRepository>(), fake<GroupRepository>(), fake<DailyProgressStore>(), gameClock)
        val service = GameService(
            fake<UserRepository>(), fake<GroupRepository>(), fake<QuestionRepository>(),
            fake<ProgressRepository> { MissionStartOutcome.NoRoster }, properties,
            MutableClock(OffsetDateTime.parse("${D}T07:10:00+09:00").toInstant(), properties.zone), gameClock, rosters, fake<QuestionBankCache>(), null,
        )

        assertThatThrownBy { runBlocking { service.startMission(UID, GID) } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(calls).containsExactly("ProgressRepository.startMission", "RosterRepository.find", "ProgressRepository.startMission")
    }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 11, 12)
        val UID = UserId(11)
        val GID = GroupId(7)
    }
}
