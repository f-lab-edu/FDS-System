package io.github.hyungkishin.transentia.relay.batch

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.slf4j.LoggerFactory
import org.springframework.batch.core.SkipListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Skip Item 처리 리스너
 *
 * 역할:
 * - Spring Batch의 retry 초과로 skip된 item을 DLQ로 이동
 * - 실패 원인 로깅
 */
@Component
class TransferOutboxSkipListener(
    private val outboxRepository: TransferEventsOutboxRepository
) : SkipListener<ClaimedRow, Pair<ClaimedRow, TransferEventAvroModel>> {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Writer에서 skip된 item 처리
     *
     * - Kafka 전송 실패 후 재시도 초과한 item
     * - DLQ로 이동 (status = DEAD_LETTER)
     */
    override fun onSkipInWrite(
        item: Pair<ClaimedRow, TransferEventAvroModel>,
        t: Throwable
    ) {
        val (claimedRow, avroModel) = item
        
        try {
            outboxRepository.markAsDeadLetter(
                eventId = avroModel.eventId,
                error = "${t.javaClass.simpleName}: ${t.message}",
                now = Instant.now()
            )
            
            log.error(
                "[DLQ] Kafka 전송 재시도 초과 - eventId={}, attempt={}, error={}",
                avroModel.eventId,
                claimedRow.attemptCount,
                t.message,
                t
            )
        } catch (e: Exception) {
            log.error(
                "[DLQ] DLQ 이동 실패 - eventId={}, error={}",
                avroModel.eventId,
                e.message,
                e
            )
        }
    }

    /**
     * Reader에서 skip된 item 처리
     *
     * - DB 조회 실패 등
     */
    override fun onSkipInRead(t: Throwable) {
        log.warn("[Skip-Read] Reader skip 발생: {}", t.message, t)
    }

    /**
     * Processor에서 skip된 item 처리
     *
     * - Avro 변환 실패 등
     * - ClaimedRow 받음 (Processor의 input)
     */
    override fun onSkipInProcess(
        item: ClaimedRow,
        t: Throwable
    ) {
        try {
            outboxRepository.markAsDeadLetter(
                eventId = item.eventId,
                error = "${t.javaClass.simpleName}: ${t.message}",
                now = Instant.now()
            )
            
            log.error(
                "[DLQ] Processor 변환 실패 - eventId={}, error={}",
                item.eventId,
                t.message,
                t
            )
        } catch (e: Exception) {
            log.error(
                "[DLQ] DLQ 이동 실패 - eventId={}, error={}",
                item.eventId,
                e.message,
                e
            )
        }
    }
}
