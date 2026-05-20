package io.github.hyungkishin.transentia.relay.model

/**
 * 송금 이벤트 payload 구조
 */
data class TransferPayload(
    val transactionId: Long,
    val senderId: Long,
    val receiverUserId: Long,
    val amount: Long,
    val status: String,
    val occurredAt: Long,
)
