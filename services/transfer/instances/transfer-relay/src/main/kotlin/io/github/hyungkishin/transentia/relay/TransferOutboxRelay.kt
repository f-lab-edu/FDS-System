package io.github.hyungkishin.transentia.relay

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.relay.component.OutboxEventWriter
import io.github.hyungkishin.transentia.relay.component.OutboxEventProcessor
import io.github.hyungkishin.transentia.relay.component.OutboxItemReader
import io.github.hyungkishin.transentia.relay.component.OutboxWorker
import io.github.hyungkishin.transentia.relay.config.OutboxRelayConfig
import io.github.hyungkishin.transentia.relay.model.ProcessingResult
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Outbox 이벤트를 Kafka로 전송하는 Relay
 *
 * Spring Batch Multi-threaded Step 패턴 적용
 * - ItemReader: Thread-safe하게 이벤트 읽기
 * - ItemProcessor: 데이터 변환
 * - ItemWriter: Kafka 전송
 */
@Component
class TransferOutboxRelay(
    private val outboxRepository: TransferEventsOutboxRepository,
    private val reader: OutboxItemReader,
    private val processor: OutboxEventProcessor,
    private val writer: OutboxEventWriter,
    private val config: OutboxRelayConfig,
    @Qualifier("outboxExecutorService") private val executorService: ExecutorService,
    @Value("\${app.kafka.topics.transfer-events}") private val topicName: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var consecutiveEmptyCount = 0

    @Scheduled(
        fixedDelayString = "\${app.outbox.relay.fixedDelayMs:1000}",
        initialDelayString = "\${app.outbox.relay.initialDelayMs:5000}"
    )
    fun run() {
        try {
            val startTime = System.currentTimeMillis()
            val now = Instant.now()

            reader.reset()

            val result = processBatch()

            if (result.totalProcessed == 0) {
                handleEmptyBatch()
                return
            }

            consecutiveEmptyCount = 0
            val processingTime = System.currentTimeMillis() - startTime

            // 성공 처리
            if (result.successIds.isNotEmpty()) {
                outboxRepository.markAsPublished(result.successIds, now)

                log.info(
                    "Published {} events ({}% success) in {}ms",
                    result.successIds.size,
                    "%.1f".format(result.successRate * 100),
                    processingTime
                )
            }

            // 실패 처리
            if (result.failedEvents.isNotEmpty()) {
                handleFailedEvents(result.failedEvents, now)
            }

            // 성능 모니터링
            if (processingTime > config.slowProcessingThresholdMs) {
                log.warn("Slow batch processing: {}ms for {} events", processingTime, result.totalProcessed)
            }

        } catch (e: Exception) {
            log.error("Relay batch processing failed", e)
        }
    }

    private fun processBatch(): ProcessingResult {
        val successIds = ConcurrentLinkedQueue<Long>()
        val failedEvents = ConcurrentLinkedQueue<ProcessingResult.FailedEvent>()

        // Worker 생성 (Reader-Processor-Writer 조합)
        val workers = (1..config.threadPoolSize).map { workerId ->
            OutboxWorker(
                workerId = workerId,
                reader = reader,
                processor = processor,
                writer = writer,
                topicName = topicName,
                successIds = successIds,
                failedEvents = failedEvents
            )
        }

        // 병렬 실행
        val futures = workers.map { executorService.submit(it) }

        try {
            futures.forEach { it.get(config.timeoutSeconds, TimeUnit.SECONDS) }
        } catch (e: Exception) {
            log.error("Worker execution failed", e)
            futures.forEach { it.cancel(true) }
        }

        return ProcessingResult(
            successIds = successIds.toList(),
            failedEvents = failedEvents.toList()
        )
    }

    private fun handleFailedEvents(failedEvents: List<ProcessingResult.FailedEvent>, now: Instant) {
        log.warn("Failed to publish {} events", failedEvents.size)

        failedEvents.forEach { failed ->
            val backoffMillis = calculateBackoff(failed.attemptCount)
            outboxRepository.markFailedWithBackoff(
                id = failed.eventId,
                cause = failed.error,
                backoffMillis = backoffMillis,
                now = now
            )
        }
    }

    /**
     * 지수 백오프 계산
     *
     * 1회: 5초
     * 2회: 10초
     * 3회: 20초
     * 4회: 40초
     * 5회: DEAD_LETTER
     */
    private fun calculateBackoff(attemptCount: Int): Long {
        return config.baseBackoffMs * (1L shl (attemptCount - 1))
    }

    private fun handleEmptyBatch() {
        consecutiveEmptyCount++
        if (consecutiveEmptyCount > 3) {
            Thread.sleep(3000)
        }
    }

    @PreDestroy
    fun cleanup() {
        log.info("executor service 종료")
        executorService.shutdown()

        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("강제 종료")
                executorService.shutdownNow()

                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate")
                }
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
