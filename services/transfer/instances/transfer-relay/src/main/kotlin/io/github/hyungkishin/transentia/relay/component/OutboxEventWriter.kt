package io.github.hyungkishin.transentia.relay.component

import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import io.github.hyungkishin.transentia.infrastructure.kafka.producer.service.KafkaProducer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * TransferEventAvroModel을 Kafka로 전송한다.
 */
@Component
class OutboxEventWriter(
    private val kafkaProducer: KafkaProducer<String, TransferEventAvroModel>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 단일 아이템 쓰기
     * 
     * @throws Exception Kafka 전송 실패 시 예외
     */
    fun write(topicName: String, event: TransferEventAvroModel) {
        try {
            kafkaProducer.sendSync(topicName, event)
            log.debug("Successfully wrote event: eventId={}", event.eventId)
        } catch (e: Exception) {
            log.error("Failed to write event: eventId={}, error={}", event.eventId, e.message)
            throw e
        }
    }
}
