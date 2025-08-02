package io.github.hyungkishin.transentia.domain.model

import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.domain.common.enums.TransactionStatus
import java.time.LocalDateTime

class Transaction private constructor(
    val id: TransferId,
    val senderUserId: UserId,
    val receiverUserId: UserId,
    val amount: Money,
    var status: TransactionStatus,
    val createdAt: LocalDateTime,
    var receivedAt: LocalDateTime? = null
) {

    companion object {
        fun request(
            transactionId: TransferId,
            senderUserId: UserId,
            receiverUserId: UserId,
            amount: Money
        ): Transaction {
            require(senderUserId != receiverUserId) { "송신자와 수신자는 동일할 수 없습니다." }
            require(amount.isPositive()) { "송금 금액은 0보다 커야 합니다." }

            return Transaction(
                id = transactionId,
                senderUserId = senderUserId,
                receiverUserId = receiverUserId,
                amount = amount,
                status = TransactionStatus.PENDING,
                createdAt = LocalDateTime.now()
            )
        }
    }

    fun complete(receivedAt: LocalDateTime = LocalDateTime.now()) {
        check(status == TransactionStatus.PENDING) { "PENDING 상태만 완료할 수 있습니다." }
        status = TransactionStatus.COMPLETED
        this.receivedAt = receivedAt
    }

    fun fail(reason: String? = null) {
        check(status == TransactionStatus.PENDING) { "PENDING 상태만 실패 처리할 수 있습니다." }
        status = TransactionStatus.FAILED
        // TODO: 실패 사유 로그를 남기려면 별도 도메인 이벤트/로그 필요
    }

    fun correct() {
        check(status == TransactionStatus.COMPLETED || status == TransactionStatus.FAILED) {
            "정정은 완료되었거나 실패된 상태에서만 가능합니다."
        }
        status = TransactionStatus.CORRECTED
    }
}

