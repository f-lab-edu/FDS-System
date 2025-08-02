package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.domain.common.enums.TransactionStatus
import io.github.hyungkishin.transentia.domain.model.Money
import io.github.hyungkishin.transentia.domain.model.Transaction
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "transactions")
class TransactionJpaEntity(

    @Id
    val id: Long, // ID는 도메인에서 Snowflake로 생성되므로. JPA는 생성하지 않는다.

    @Column(nullable = false)
    val senderUserId: Long,

    @Column(nullable = false)
    val receiverUserId: Long,

    @Column(nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TransactionStatus,

    val receivedAt: LocalDateTime? = null

) : BaseEntity() {

    fun toDomain(): Transaction = Transaction.request(
        transactionId = TransferId(id),
        senderUserId = UserId(senderUserId),
        receiverUserId = UserId(receiverUserId),
        amount = Money.fromRawValue(amount)
    ).apply {
        when (status) {
            TransactionStatus.COMPLETED -> complete(receivedAt ?: createdAt)
            TransactionStatus.FAILED -> fail()
            TransactionStatus.CORRECTED -> correct()
            TransactionStatus.PENDING -> {
                // do nothing, 그대로 PENDING 상태 유지
            }
        }
    }

    companion object {
        fun from(domain: Transaction): TransactionJpaEntity =
            TransactionJpaEntity(
                id = domain.id.value,
                senderUserId = domain.senderUserId.value,
                receiverUserId = domain.receiverUserId.value,
                amount = domain.amount.rawValue,
                status = domain.status,
                receivedAt = domain.receivedAt
            )
    }
}

