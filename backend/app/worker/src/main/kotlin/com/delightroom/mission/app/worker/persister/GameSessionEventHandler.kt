package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.core.domain.event.StreamEvent
import com.delightroom.mission.core.domain.progress.GameSessionRecords
import com.delightroom.mission.core.domain.progress.MissionStartedRecord
import com.delightroom.mission.core.domain.progress.QuestionShownRecord
import com.delightroom.mission.core.domain.progress.ShownWrite
import com.delightroom.mission.core.port.DailyProgressStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * game-session 의 persister 처리기(LLD game-session §3.7) — `mission.started`(Q11) · `question.shown`(Q12).
 *
 * 칸을 [GameSessionRecords] 로 읽고(틀리면 [MalformedEventException]) 저장소 문장 하나를 부른다 — 돌아오면 커밋된 것이다.
 * `question.shown` 이 다른 (날짜, 번호)로 이미 낸 문제와 부딪히면 거부하지 않고(prd D15) 운영 로그 한 줄을 남긴다(HLD game-session §4.1).
 * 같은 (날짜, 번호)면 같은 이벤트의 재처리라 로그 없음.
 */
@Component
class GameSessionEventHandler(
    private val dailyProgress: DailyProgressStore,
) : PersisterEventHandler {
    override val types: Set<String> = GameSessionRecords.TYPES

    override suspend fun handle(event: StreamEvent) {
        val record = try {
            GameSessionRecords.parse(event.fields)
        } catch (e: IllegalArgumentException) {
            throw MalformedEventException("${event.type} ${event.id}: ${e.message}", e)
        }
        when (record) {
            is MissionStartedRecord -> dailyProgress.applyMissionStarted(record)
            is QuestionShownRecord -> {
                val write = dailyProgress.recordShown(record)
                if (write is ShownWrite.Duplicate) opsLog.warn(servedDuplicate(record))
            }
        }
    }

    private companion object {
        val opsLog = LoggerFactory.getLogger(GameSessionEventHandler::class.java)
        val json: JsonMapper = JsonMapper.builder().build()

        /** `{"event":"question.served_duplicate","date":…,"loginId":…,"questionNo":…,"questionId":…}` — 칸 순서는 HLD §4.1 그대로. */
        fun servedDuplicate(record: QuestionShownRecord): String =
            json.writeValueAsString(
                linkedMapOf(
                    "event" to "question.served_duplicate",
                    "date" to record.date.toString(),
                    "loginId" to record.loginId,
                    "questionNo" to record.questionNo,
                    "questionId" to record.questionPk,
                ),
            )
    }
}
