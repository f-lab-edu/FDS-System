package io.github.hyungkishin.transentia.api.ui.response

import java.time.LocalDateTime

data class TransferResponse(
    val transactionId: Long,
    val status: String,
    val createdAt: LocalDateTime,
    val receivedAt: LocalDateTime?
) {
    companion object {
        fun of(
            transactionId: Long,
            status: String,
            createdAt: LocalDateTime,
            receivedAt: LocalDateTime?
        ): TransferResponse {
            return TransferResponse(
                transactionId = transactionId,
                status = status,
                createdAt = createdAt,
                receivedAt = receivedAt
            )
        }
    }
}