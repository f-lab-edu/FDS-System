package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.enums.TransactionStatus
import io.github.hyungkishin.transentia.consumer.model.Money
import io.github.hyungkishin.transentia.consumer.model.Transaction
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import jakarta.persistence.*
import org.hibernate.Hibernate
import org.springframework.data.domain.Persistable
import java.time.LocalDateTime

@Entity
@Table(name = "transactions")
class TransactionJpaEntity(

    @Id
    @Column(nullable = false)
    val id: Long, // Snowflake 사전 할당 ID

    @Column(nullable = false)
    val senderUserId: Long,

    @Column(nullable = false)
    val receiverUserId: Long,

    @Column(nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TransactionStatus,

    val receivedAt: LocalDateTime? = null,

    /**
     * 낙관적 락(Optimistic Lock)
     * - 반드시 var 이어야 Hibernate가 증가된 버전을 다시 기록 가능
     * - version == null 이면 새 엔티티(persist), 아니면 기존(merge)
     */
    @Version
    var version: Long? = null

) : BaseEntity(), Persistable<Long> {

    /**
     * Persistable 구현 으로 "새 엔티티 여부"를 명시한다. Snowflake 사전 ID 여도 persist 경로를 보장
     */
    override fun getId(): Long = id
    override fun isNew(): Boolean = (version == null)

    fun toDomain(): Transaction =
        Transaction.start(
            id = TransferId(id),
            senderUserId = UserId(senderUserId),
            receiverUserId = UserId(receiverUserId),
            amount = Money.fromRawValue(amount)
        ).apply {
            when (status) {
                TransactionStatus.COMPLETED -> complete()
                TransactionStatus.FAILED -> fail("송금 실패")
                TransactionStatus.PENDING -> {}
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
                receivedAt = domain.createdAt
            )
    }

    /**
     * 프록시 안전 equals/hashCode (ID 기반)
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as TransactionJpaEntity
        return this.id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
