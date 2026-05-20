package io.github.hyungkishin.transentia.infra.rdb.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Transfer 도메인의 transactions 테이블을 FDS 가 read-only 로 참조하기 위한 엔티티.
 * 쓰기 책임은 transfer-infra 의 TransactionJpaEntity 가 가진다.
 */
@Entity(name = "FdsTransactionRead")
@Table(name = "transactions")
class TransactionReadEntity(
    @Id
    @Column(nullable = false)
    val id: Long,
    @Column(name = "sender_user_id", nullable = false)
    val senderUserId: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
