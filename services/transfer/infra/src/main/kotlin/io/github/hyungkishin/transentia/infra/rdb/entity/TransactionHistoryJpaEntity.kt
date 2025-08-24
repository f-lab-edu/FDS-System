package io.github.hyungkishin.transentia.infra.rdb.entity


import io.github.hyungkishin.transentia.consumer.enums.TransactionHistoryStatus
import io.github.hyungkishin.transentia.consumer.model.TransactionHistory
import jakarta.persistence.*
import org.hibernate.Hibernate
import java.time.LocalDateTime

@Entity
@Table(name = "transaction_histories")
class TransactionHistoryJpaEntity(

    @Id
    @Column(nullable = false)
    val id: Long, // Snowflake 기반 ID

    @Column(name = "transaction_id", nullable = false)
    val transactionId: Long, // Snowflake 기반 ID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: TransactionHistoryStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {

    companion object {
        fun from(domain: TransactionHistory): TransactionHistoryJpaEntity =
            TransactionHistoryJpaEntity(
                id = domain.id.value,
                transactionId = domain.transferId.value,
                status = domain.status,
                createdAt = domain.createdAt
            )
    }

    /**
     * 프록시 안전 equals/hashCode (ID 기반)
     */
    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as TransactionHistoryJpaEntity
        return this.id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

}