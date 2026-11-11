package com.delightroom.mission.app.api

import com.delightroom.mission.core.domain.question.QuestionCatalog
import com.delightroom.mission.core.port.QuestionRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 기동 때 문제 캐시를 한 번 적재한다(LLD game-session §1 · Q1). 빈 생성 안에서 끝내므로 웹 서버가 뜨기 전에 다 올라가
 * 적재 전 요청은 받지 않는다. 새 문제는 재기동 뒤부터 보인다(LLD §5).
 */
@Configuration(proxyBeanMethods = false)
class QuestionCatalogLoader {
    @Bean
    fun questionCatalog(questions: QuestionRepository): QuestionCatalog {
        val catalog = runBlocking { QuestionCatalog(questions.loadAll()) }
        log.info("문제 캐시 적재: {}개 (난이도별 {})", catalog.size, catalog.idsByDifficulty.mapValues { it.value.size })
        return catalog
    }

    private companion object {
        private val log = LoggerFactory.getLogger(QuestionCatalogLoader::class.java)
    }
}
