package com.delightroom.mission.app.worker.persister

import com.delightroom.mission.app.worker.WorkerTestContainers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.reactive.HandlerMapping

/**
 * `worker` 앱 기동(ORACLE platform O15 의 배선) — 웹 서버 없이 뜨고, 스프링이 띄운 [StreamPersister] 가
 * 처리기 빈을 모아 Stream 을 소비한다. 루프가 같은 Redis 를 계속 읽으므로 끝나면 컨텍스트를 닫는다.
 */
@SpringBootTest(properties = ["game.bucket-count=8", "worker.persister.idle-delay=20ms", "worker.member.enabled=false"])
@ContextConfiguration(initializers = [WorkerTestContainers.Initializer::class])
@DirtiesContext
class StreamPersisterAppTest
    @Autowired
    constructor(
        private val context: ApplicationContext,
        private val persister: StreamPersister,
        private val redis: ReactiveStringRedisTemplate,
        private val db: DatabaseClient,
    ) {
        @TestConfiguration
        class Handlers {
            @Bean
            fun probeHandler(db: DatabaseClient, tx: TransactionalOperator): PersisterEventHandler = ProbeHandler(db, tx)
        }

        @Test
        fun `O15 앱이 웹 서버 없이 뜨고 등록된 처리기로 Stream 을 소비한다`(): Unit = runBlocking {
            val probe = ProbeTable(db, redis)
            probe.reset()
            assertThat(context.getBeanNamesForType(HandlerMapping::class.java)).isEmpty()
            assertThat(persister.isRunning).isTrue()
            assertThat(persister.buckets).containsExactlyElementsOf(0 until 8)

            (1..3).forEach { probe.add(5, it) }
            eventually(what = "3행") { probe.rows().size == 3 }
            eventually(what = "pending 0") { probe.pending(5) == 0L }
            assertThat(probe.rows().map { it.seq }).containsExactly(1, 2, 3)
        }
    }
