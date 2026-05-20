package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.TransactionReadEntity
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransactionReadJpaRepository : JpaRepository<TransactionReadEntity, Long> {
    @Query(
        """
        SELECT COUNT(t)
        FROM FdsTransactionRead t
        WHERE t.senderUserId = :userId
          AND t.createdAt >= :since
        """,
    )
    fun countByUserSince(
        @Param("userId") userId: Long,
        @Param("since") since: Instant,
    ): Long
}
