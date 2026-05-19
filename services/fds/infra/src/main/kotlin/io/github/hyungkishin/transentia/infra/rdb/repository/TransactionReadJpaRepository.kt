package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.TransactionReadEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface TransactionReadJpaRepository : JpaRepository<TransactionReadEntity, Long> {
    @Query(
        """
        SELECT COUNT(t)
        FROM FdsTransactionRead t
        WHERE t.senderUserId = :userId
          AND t.createdAt >= :since
        """
    )
    fun countByUserSince(@Param("userId") userId: Long, @Param("since") since: Instant): Long
}
