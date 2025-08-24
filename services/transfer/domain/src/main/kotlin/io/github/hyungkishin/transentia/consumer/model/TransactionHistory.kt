package io.github.hyungkishin.transentia.consumer.model

import io.github.hyungkishin.transentia.common.snowflake.TransactionId
import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.enums.TransactionHistoryStatus
import java.time.LocalDateTime

class TransactionHistory private constructor(
    val id: TransactionId,
    val transferId: TransferId,
    val senderUserId: UserId,
    val receiverUserId: UserId,
    val amount: Money,
    val status: TransactionHistoryStatus,
    val reason: String?,
    val createdAt: LocalDateTime
) {
    companion object {
        fun successOf(tx: Transaction, txId: TransactionId): TransactionHistory =
            TransactionHistory(
                id = txId,
                transferId = tx.id,
                senderUserId = tx.senderUserId,
                receiverUserId = tx.receiverUserId,
                amount = tx.amount,
                status = TransactionHistoryStatus.SUCCESS,
                reason = null,
                createdAt = LocalDateTime.now()
            )

        fun failOf(tx: Transaction, txId: TransactionId, reason: String?): TransactionHistory =
            TransactionHistory(
                id = txId,
                transferId = tx.id,
                senderUserId = tx.senderUserId,
                receiverUserId = tx.receiverUserId,
                amount = tx.amount,
                status = TransactionHistoryStatus.FAIL,
                reason = reason,
                createdAt = LocalDateTime.now()
            )
    }
}
