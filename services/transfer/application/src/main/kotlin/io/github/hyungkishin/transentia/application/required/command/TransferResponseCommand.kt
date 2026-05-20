package io.github.hyungkishin.transentia.application.required.command

import io.github.hyungkishin.transentia.container.model.transaction.Transaction
import java.time.Instant

data class TransferResponseCommand(
    val transactionId: Long,
    val status: String,
    val createdAt: Instant,
    val receivedAt: Instant?,
) {
    companion object {
        fun from(tx: Transaction): TransferResponseCommand =
            TransferResponseCommand(
                transactionId = tx.id.value,
                status = tx.status.name,
                createdAt = tx.createdAt,
                receivedAt = tx.createdAt,
            )
    }
}
