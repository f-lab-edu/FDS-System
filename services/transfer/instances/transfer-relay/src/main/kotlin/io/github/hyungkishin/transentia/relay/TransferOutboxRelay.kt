package io.github.hyungkishin.transentia.relay

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.relay.component.OutboxEventProcessor
import io.github.hyungkishin.transentia.relay.component.OutboxEventWriter
import io.github.hyungkishin.transentia.relay.component.OutboxItemReader
import io.github.hyungkishin.transentia.relay.component.OutboxWorker
import io.github.hyungkishin.transentia.relay.config.OutboxRelayConfig
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Outbox 이벤트를 Kafka로 전송하는 Relay
 */
@Component
class TransferOutboxRelay(
    private val reader: OutboxItemReader,
    private val processor: OutboxEventProcessor,
    private val writer: OutboxEventWriter,
    private val repository: TransferEventsOutboxRepository,
    private val config: OutboxRelayConfig,
    @Qualifier("outboxExecutorService") private val executor: ExecutorService,
    @Value("\${app.kafka.topics.transfer-events}") private val topicName: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.outbox.relay.fixedDelayMs:2000}",
        initialDelayString = "\${app.outbox.relay.initialDelayMs:5000}"
    )
    fun runBatch() {
        val startTime = System.currentTimeMillis()
        
        try {
            reader.reset()
            
            val workers = (1..config.threadPoolSize).map { workerId ->
                OutboxWorker(
                    workerId = workerId,
                    reader = reader,
                    processor = processor,
                    writer = writer,
                    repository = repository,
                    config = config,
                    topicName = topicName
                )
            }
            
            val futures = workers.map { worker ->
                executor.submit(worker)
            }
            
            futures.forEach { future ->
                try {
                    future.get(config.timeoutSeconds, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    log.error("워커 타임아웃", e)
                    future.cancel(true)
                }
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            log.debug("배치 완료: {}ms", elapsed)
            
        } catch (e: Exception) {
            log.error("배치 실행 오류", e)
        }
    }
    
    @PreDestroy
    fun shutdown() {
        log.info("TransferOutboxRelay 종료")
        executor.shutdown()
        
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("강제 종료")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
