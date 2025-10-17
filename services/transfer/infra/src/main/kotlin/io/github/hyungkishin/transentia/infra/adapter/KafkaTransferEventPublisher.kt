package io.github.hyungkishin.transentia.infra.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.hyungkishin.transentia.application.port.TransferEventPublisher
import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventType
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferStatus
import io.github.hyungkishin.transentia.infrastructure.kafka.producer.service.KafkaProducer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.*

@Component
class KafkaTransferEventPublisher(
    private val kafkaProducer: KafkaProducer<String, TransferEventAvroModel>,
    private val outboxRepository: TransferEventsOutboxRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.kafka.topics.transfer-events}") private val topicName: String
) : TransferEventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: TransferCompleted) {
        try {
            val avroModel = TransferEventAvroModel.newBuilder()
                .setEventId(event.eventId)
                .setEventType(TransferEventType.TRANSFER_COMPLETED)
                .setAggregateId(event.transactionId.toString())
                .setTransactionId(event.transactionId)
                .setSenderId(event.senderUserId)
                .setReceiverId(event.receiverUserId)
                .setAmount(event.amount.toString())
                .setStatus(TransferStatus.COMPLETED)
                .setOccurredAt(event.occurredAt.toEpochMilli())
                .setHeaders(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "eventType" to "TRANSFER_COMPLETED",
                            "eventVersion" to "v1",
                            "traceId" to (MDC.get("traceId") ?: UUID.randomUUID().toString()),
                            "producer" to "transfer-api",
                            "contentType" to "application/json"
                        )
                    )
                )
                .setCreatedAt(System.currentTimeMillis())
                .build()

            kafkaProducer.sendSync(topicName, avroModel)
            
            outboxRepository.markAsPublished(listOf(event.eventId), Instant.now())
            log.debug("Kafka 전송 및 outbox PUBLISHED 완료: eventId={}", event.eventId)
            
        } catch (e: Exception) {
            log.warn("Kafka 전송 실패 (relay 재시도): eventId={}, error={}", event.eventId, e.message)
        }
    }
}
