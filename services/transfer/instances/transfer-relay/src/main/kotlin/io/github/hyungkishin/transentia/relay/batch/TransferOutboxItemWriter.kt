package io.github.hyungkishin.transentia.relay.batch

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import io.github.hyungkishin.transentia.infrastructure.kafka.producer.service.KafkaProducer
import io.github.hyungkishin.transentia.relay.exception.RetryableKafkaException
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Outbox Event Kafka Writer
 *
 * 흐름:
 * 1. Kafka 전송 (동기)
 * 2. 성공 시: Outbox PUBLISHED 업데이트
 * 3. 실패 시: RetryableKafkaException throw
 *    - FaultTolerantStepConfigurer가 retry 처리 (지수 백오프)
 *    - retry 초과 시: SkipListener가 DLQ로 이동
 */
@Component
class TransferOutboxItemWriter(
    private val kafkaProducer: KafkaProducer<String, TransferEventAvroModel>,
    private val outboxRepository: TransferEventsOutboxRepository,
    @Value("\${app.kafka.topics.transfer-events}")
    private val topicName: String,
) : ItemWriter<Pair<ClaimedRow, TransferEventAvroModel>> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun write(chunk: Chunk<out Pair<ClaimedRow, TransferEventAvroModel>>) {
        val successIds = mutableListOf<Long>()

        chunk.items.forEach { (claimedRow, avroModel) ->
            try {
                // 1. Kafka 전송 (동기)
                kafkaProducer.sendSync(topicName, avroModel)

                // 2. 성공 ID 수집
                successIds.add(avroModel.eventId)

                log.debug(
                    "Kafka 전송 성공: eventId={}, attempt={}",
                    avroModel.eventId,
                    claimedRow.attemptCount,
                )
            } catch (e: Exception) {
                log.warn(
                    "Kafka 전송 실패: eventId={}, attempt={}, error={}",
                    avroModel.eventId,
                    claimedRow.attemptCount,
                    e.message,
                )

                // 3. 실패 시 예외 throw (Spring Batch가 retry/skip 처리)
                throw RetryableKafkaException(
                    "Kafka 전송 실패: eventId=${avroModel.eventId}",
                    e,
                )
            }
        }

        // 4. 성공한 이벤트 Outbox 업데이트 (PUBLISHED)
        if (successIds.isNotEmpty()) {
            try {
                outboxRepository.markAsPublished(successIds, Instant.now())
                log.info("Chunk 전송 완료: {} 건", successIds.size)
            } catch (e: Exception) {
                log.error("Outbox 업데이트 실패", e)
                throw e
            }
        }
    }
}
