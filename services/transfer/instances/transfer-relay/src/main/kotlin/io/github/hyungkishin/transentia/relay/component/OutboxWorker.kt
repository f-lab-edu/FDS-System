package io.github.hyungkishin.transentia.relay.component

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.relay.config.OutboxRelayConfig
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Outbox Worker: Reader → Processor → Writer 파이프라인
 * 
 * 각 워커가 독립적으로 실행:
 * 1. Reader.read() - DB에서 읽기
 * 2. Processor.process() - Avro 변환
 * 3. Writer.write() - Kafka 전송
 * 4. 성공/실패 처리
 */
class OutboxWorker(
    private val workerId: Int,
    private val reader: OutboxItemReader,
    private val processor: OutboxEventProcessor,
    private val writer: OutboxEventWriter,
    private val repository: TransferEventsOutboxRepository,
    private val config: OutboxRelayConfig,
    private val topicName: String
) : Runnable {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run() {
        var processedCount = 0
        var failedCount = 0
        
        try {
            while (true) {
                // 1. Reader: 이벤트 읽기
                val item = reader.read() ?: break
                
                val now = Instant.now()
                
                try {
                    // 2. Processor: Avro 변환
                    val avroModel = processor.process(item)
                    
                    // 3. Writer: Kafka 전송
                    writer.write(topicName, avroModel)
                    
                    // 4. 성공 처리
                    repository.markAsPublished(listOf(item.eventId), now)
                    processedCount++
                    
                    log.debug("[Worker-{}] 성공: eventId={}", workerId, item.eventId)
                    
                } catch (e: Exception) {
                    // 5. 실패 처리
                    handleFailure(item.eventId, item.attemptCount, e, now)
                    failedCount++
                }
            }
            
            if (processedCount > 0 || failedCount > 0) {
                log.info("[Worker-{}] 완료: 성공={}, 실패={}", workerId, processedCount, failedCount)
            }
            
        } catch (e: Exception) {
            log.error("[Worker-{}] 예외 발생", workerId, e)
        }
    }

    /**
     * 실패 처리: 재시도 or DLQ
     */
    private fun handleFailure(
        eventId: Long,
        attemptCount: Int,
        error: Exception,
        now: Instant
    ) {
        val nextAttempt = attemptCount + 1
        val errorMessage = error.message ?: error.javaClass.simpleName
        
        try {
            if (nextAttempt > config.maxAttempts) {
                // DLQ 전환
                repository.markAsDeadLetter(eventId, errorMessage, now)
                log.error("[Worker-{}] DLQ: eventId={}, attempts={}", workerId, eventId, attemptCount)
            } else {
                // 재시도 예약 (지수 백오프)
                val backoffMs = config.baseBackoffMs * (1L shl (nextAttempt - 1))
                repository.markForRetry(
                    eventId = eventId,
                    attemptCount = nextAttempt,
                    nextRetryAt = now.plusMillis(backoffMs),
                    error = errorMessage,
                    now = now
                )
                log.warn(
                    "[Worker-{}] 재시도: eventId={}, attempt={}/{}, nextRetry={}ms",
                    workerId, eventId, nextAttempt, config.maxAttempts, backoffMs
                )
            }
        } catch (e: Exception) {
            log.error("[Worker-{}] 실패 처리 오류: eventId={}", workerId, eventId, e)
        }
    }
}
