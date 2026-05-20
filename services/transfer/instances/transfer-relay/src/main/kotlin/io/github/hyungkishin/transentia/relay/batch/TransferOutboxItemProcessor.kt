package io.github.hyungkishin.transentia.relay.batch

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventType
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferStatus
import io.github.hyungkishin.transentia.relay.exception.InvalidEventDataException
import io.github.hyungkishin.transentia.relay.model.TransferPayload
import org.springframework.batch.item.ItemProcessor
import org.springframework.stereotype.Component

@Component
class TransferOutboxItemProcessor(
    private val objectMapper: ObjectMapper,
) : ItemProcessor<ClaimedRow, Pair<ClaimedRow, TransferEventAvroModel>> {
    override fun process(item: ClaimedRow): Pair<ClaimedRow, TransferEventAvroModel> {
        try {
            val payload = objectMapper.readValue(item.payload, TransferPayload::class.java)

            val avroModel =
                TransferEventAvroModel
                    .newBuilder()
                    .setEventId(item.eventId)
                    .setEventType(
                        if (payload.status == "COMPLETED") {
                            TransferEventType.TRANSFER_COMPLETED
                        } else {
                            TransferEventType.TRANSFER_FAILED
                        },
                    ).setTransactionId(payload.transactionId)
                    .setSenderId(payload.senderId)
                    .setReceiverId(payload.receiverUserId)
                    .setAmount(payload.amount.toString())
                    .setStatus(TransferStatus.valueOf(payload.status))
                    .setOccurredAt(payload.occurredAt)
                    .setHeaders(item.headers)
                    .setCreatedAt(System.currentTimeMillis())
                    .build()

            return item to avroModel
        } catch (e: Exception) {
            throw InvalidEventDataException(
                "이벤트 변환 실패: eventId=${item.eventId}",
                e,
            )
        }
    }
}
