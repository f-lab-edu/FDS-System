package io.github.hyungkishin.transentia.api.ui.response

import io.github.hyungkishin.transentia.application.required.command.TransferResponseCommand
import java.time.LocalDateTime

data class TransferResponse(
    val transactionId: Long, val status: String, val createdAt: LocalDateTime, val receivedAt: LocalDateTime?
) {
    companion object {
        fun of(
            command: TransferResponseCommand,
        ): TransferResponse {
            return TransferResponse(
                transactionId = command.transactionId,
                status = command.status,
                createdAt = command.createdAt,
                receivedAt = command.receivedAt
            )
        }
    }
}