package io.github.hyungkishin.transentia.api.ui.response

import java.time.LocalDateTime

data class TransferResponse(
    val transactionId: Long,
    val status: String,
    val createdAt: LocalDateTime,
    val receivedAt: LocalDateTime?
)