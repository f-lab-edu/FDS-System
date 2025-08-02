package io.github.hyungkishin.transentia.application.port.`out`

import io.github.hyungkishin.transentia.domain.model.Transaction
import java.time.LocalDateTime

data class TransferResponseCommand(
    val transactionId: Long,
    val status: String,
    val createdAt: LocalDateTime,
    val receivedAt: LocalDateTime?
) {
    companion object {
        fun from(tx: Transaction): TransferResponseCommand {
            return TransferResponseCommand(
                transactionId = tx.id.value,
                status = tx.status.name,
                createdAt = tx.createdAt,
                receivedAt = tx.receivedAt
            )
        }
    }
}
