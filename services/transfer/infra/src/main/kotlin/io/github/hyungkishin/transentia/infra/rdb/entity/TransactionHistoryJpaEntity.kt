package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.container.model.transaction.TransactionHistory
import jakarta.persistence.*
import java.time.Instant
import org.hibernate.Hibernate

@Entity
@Table(
    name = "transaction_histories",
    indexes = [
        Index(name = "idx_txh_txid_created_at", columnList = "transaction_id, created_at DESC"),
        Index(name = "idx_txh_status_created_at", columnList = "status, created_at DESC"),
    ],
)
class TransactionHistoryJpaEntity(
    @Id
    @Column(nullable = false)
    val id: Long, // Snowflake
    @Column(name = "transaction_id", nullable = false)
    val transactionId: Long, // Snowflake
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        fun from(domain: TransactionHistory): TransactionHistoryJpaEntity =
            TransactionHistoryJpaEntity(
                id = domain.id.value,
                transactionId = domain.transactionId.value,
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as TransactionHistoryJpaEntity
        return this.id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
