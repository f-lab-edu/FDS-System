package io.github.hyungkishin.transentia.application.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted
import io.github.hyungkishin.transentia.container.event.TransferEvent
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.util.*

@Component
class OutboxEventMapper(
    private val objectMapper: ObjectMapper
) {
    fun toOutboxEvent(event: TransferCompleted): TransferEvent {
        return TransferEvent(
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
                    "producer" to "transfer-api",
                    "contentType" to "application/json"
                )
            )
        )
    }
}
