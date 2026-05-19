package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.model.Currency
import io.github.hyungkishin.transentia.common.model.Money
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.enums.TransactionStatus
import io.github.hyungkishin.transentia.container.model.transaction.Transaction
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import jakarta.persistence.*
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Instant

@Entity
@Table(
    name = "transactions",
    indexes = [
        Index(name = "idx_tx_sender_created", columnList = "sender_user_id, created_at DESC"),
        Index(name = "idx_tx_receiver_created", columnList = "receiver_user_id, created_at DESC"),
        Index(name = "idx_tx_status_updated", columnList = "status, status_updated_at DESC")
    ]
)
class TransactionJpaEntity(
    @Id
    @Column(nullable = false)
    val id: Long,

    @Column(name = "sender_user_id", nullable = false)
    val senderUserId: Long,

    @Column(name = "receiver_user_id", nullable = false)
    val receiverUserId: Long,

    @Column(name = "amount", nullable = false)
    val amount: Long,

    @Column(name = "currency", nullable = false)
    @Enumerated(EnumType.STRING)
    val currency: Currency,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    var status: TransactionStatus,

    @Column(name = "received_at")
    val receivedAt: Instant? = null,

    @Column(name = "status_updated_at", insertable = false, updatable = false)
    val statusUpdatedAt: Instant? = null,

    @Version
    var version: Long? = null

) : BaseEntity(), Persistable<Long> {

    override fun getId(): Long = id
    override fun isNew(): Boolean = (version == null)

    fun toDomain(): Transaction =
        Transaction.of(
            id = SnowFlakeId(id),
            senderSnowFlakeId = SnowFlakeId(senderUserId),
            receiverSnowFlakeId = SnowFlakeId(receiverUserId),
            amount = Amount(Money.fromRawValue(amount), currency),
        ).apply {
            when (status) {
                TransactionStatus.COMPLETED -> complete()
                TransactionStatus.FAILED -> {}
                TransactionStatus.PENDING -> {}
            }
        }

    companion object {
        fun from(domain: Transaction): TransactionJpaEntity =
            TransactionJpaEntity(
                id = domain.id.value,
                senderUserId = domain.senderId.value,
                receiverUserId = domain.receiverId.value,
                status = domain.status,
                amount = domain.amount.money.rawValue,
                currency = domain.amount.currency,
                // NOTE: Transaction 도메인에 receivedAt 개념이 없어 createdAt 으로 대체한다.
                // 추후 입금 처리 완료 시각을 도메인 모델에 분리하려면 Transaction.receivedAt 필드를 추가하고 수신측 적재 시점에 set 한다.
                receivedAt = domain.createdAt
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as TransactionJpaEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}