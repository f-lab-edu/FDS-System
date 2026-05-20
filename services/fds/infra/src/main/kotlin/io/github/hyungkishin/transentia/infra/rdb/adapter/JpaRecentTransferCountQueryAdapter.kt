package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.required.RecentTransferCountQueryPort
import io.github.hyungkishin.transentia.infra.rdb.repository.TransactionReadJpaRepository
import java.time.Instant
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * transactions 테이블을 read-only 로 참조하여 최근 N분 송금 횟수를 조회.
 * idx_tx_sender_created (sender_user_id, created_at DESC) 인덱스를 활용한다.
 */
@Repository
class JpaRecentTransferCountQueryAdapter(
    private val transactionReadRepository: TransactionReadJpaRepository,
) : RecentTransferCountQueryPort {
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    override fun countByUserSince(
        userId: Long,
        since: Instant,
    ): Long = transactionReadRepository.countByUserSince(userId, since)
}
