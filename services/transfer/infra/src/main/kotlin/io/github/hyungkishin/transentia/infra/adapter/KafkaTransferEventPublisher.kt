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
                .setEventId(event.transactionId)
                .setEventType(TransferEventType.TRANSFER_COMPLETED)
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
            log.info("Kafka 전송 성공: eventId={}", event.transactionId)

        } catch (e: Exception) {
            log.warn("Kafka 전송 실패, Outbox 저장: eventId={}, error={}", event.transactionId, e.message)
            saveToOutbox(event)
        }
    }

    private fun saveToOutbox(event: TransferCompleted) {
        try {
            val outboxEvent = io.github.hyungkishin.transentia.container.event.TransferEvent(
                eventId = event.transactionId,
                aggregateType = "Transaction",
                eventType = "TRANSFER_COMPLETED",
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "transactionId" to event.transactionId,
                        "senderId" to event.senderUserId,
                        "receiverId" to event.receiverUserId,
                        "amount" to event.amount,
                        "status" to "COMPLETED",
                        "occurredAt" to event.occurredAt.toEpochMilli()
                    )
                ),
                headers = objectMapper.writeValueAsString(
                    mapOf(
                        "eventType" to "TRANSFER_COMPLETED",
                        "eventVersion" to "v1",
                        "traceId" to (MDC.get("traceId") ?: UUID.randomUUID().toString()),
                        "producer" to "transfer-api-fallback",
                        "contentType" to "application/json"
                    )
                )
            )
            outboxRepository.save(outboxEvent, Instant.now())
            log.info("Outbox 저장 성공: eventId={}", event.transactionId)
        } catch (outboxEx: Exception) {
            log.error("Outbox 저장 실패: eventId={}, error={}", event.transactionId, outboxEx.message, outboxEx)
        }
    }
}
