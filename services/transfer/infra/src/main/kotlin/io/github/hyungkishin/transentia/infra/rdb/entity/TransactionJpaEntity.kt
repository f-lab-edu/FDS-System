package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.shared.snowflake.TransferId
import io.github.hyungkishin.transentia.shared.snowflake.UserId
import io.github.hyungkishin.transentia.domain.common.enums.TransactionStatus
import io.github.hyungkishin.transentia.domain.model.Money
import io.github.hyungkishin.transentia.domain.model.Transaction
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import io.github.hyungkishin.transentia.infra.config.CustomEnumType
import jakarta.persistence.*
import org.hibernate.annotations.Type
import java.time.LocalDateTime

@Entity
@Table(name = "transactions")
class TransactionJpaEntity(

    @Id
    val id: Long, // ID는 도메인에서 Snowflake로 생성하므로 JPA는 생성하지 않는다.

    @Column(nullable = false)
    val senderUserId: Long,

    @Column(nullable = false)
    val receiverUserId: Long,

    @Column(nullable = false)
    val amount: Long,

    @Type(CustomEnumType::class)
    @Column(nullable = false)
    val status: TransactionStatus,

    val receivedAt: LocalDateTime? = null,

    /**
     * 낙관적 락(Optimistic Lock)을 위한 버전 필드.
     * JPA가 update 시 where 절에 version을 포함시켜
     * 동시에 수정된 경우 예외(OptimisticLockException)를 발생시킨다.
     */
    @Version
    val version: Long? = null

) : BaseEntity() {

    fun toDomain(): Transaction =
        Transaction.of(
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
                    // 그대로 PENDING 상태 유지
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
