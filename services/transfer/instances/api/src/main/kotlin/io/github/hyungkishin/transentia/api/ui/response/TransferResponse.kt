package io.github.hyungkishin.transentia.api.ui.response

import io.github.hyungkishin.transentia.application.required.command.TransferResponseCommand
import java.time.Instant

data class TransferResponse(
    val transactionId: Long,
    val status: String,
    val createdAt: Instant,
    val receivedAt: Instant?,
) {
    companion object {
        fun of(command: TransferResponseCommand): TransferResponse =
            TransferResponse(
                transactionId = command.transactionId,
                status = command.status,
                createdAt = command.createdAt,
                receivedAt = command.receivedAt,
            )
    }
}
