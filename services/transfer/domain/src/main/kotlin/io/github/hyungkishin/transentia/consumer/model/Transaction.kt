package io.github.hyungkishin.transentia.consumer.model

import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.enums.TransactionStatus
import io.github.hyungkishin.transentia.consumer.event.TransferCompletedEvent
import io.github.hyungkishin.transentia.consumer.event.TransferFailedEvent
import java.time.LocalDateTime

class Transaction private constructor(
    val id: TransferId,
    val senderUserId: UserId,
    val receiverUserId: UserId,
    val amount: Money,
    var status: TransactionStatus,
    var createdAt: LocalDateTime,
    var failReason: String? = null,
) {

    companion object {

        fun start(
            id: TransferId,
            senderUserId: UserId,
            receiverUserId: UserId,
            amount: Money
        ): Transaction {
            require(senderUserId != receiverUserId) { "송신자와 수신자는 동일할 수 없습니다." }
            require(amount.isPositive()) { "송금 금액은 0보다 커야 합니다." }
            return Transaction(
                id = id,
                senderUserId = senderUserId,
                receiverUserId = receiverUserId,
                amount = amount,
                status = TransactionStatus.PENDING,
                createdAt = LocalDateTime.now()
            )
        }

    }

    fun complete(): TransferCompletedEvent {
        check(status == TransactionStatus.PENDING) { "PENDING 상태만 완료할 수 있습니다." }
        status = TransactionStatus.COMPLETED
        return TransferCompletedEvent(id, senderUserId, receiverUserId, amount)
    }

    fun fail(reason: String): TransferFailedEvent {
        check(status == TransactionStatus.PENDING) { "PENDING 상태만 실패 처리할 수 있습니다." }
        status = TransactionStatus.FAILED
        failReason = reason
        return TransferFailedEvent(id, reason)
    }

}