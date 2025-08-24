package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.required.TransactionHistoryRepository
import io.github.hyungkishin.transentia.consumer.model.TransactionHistory
import io.github.hyungkishin.transentia.infra.rdb.entity.TransactionHistoryJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.TransactionHistoryJpaRepository
import org.springframework.stereotype.Component

@Component
class TransactionHistoryPersistenceAdapter(
    private val jpaRepository: TransactionHistoryJpaRepository,
) : TransactionHistoryRepository {

    override fun save(transactionHistory: TransactionHistory) {
        jpaRepository.save(TransactionHistoryJpaEntity.from(transactionHistory))
    }

}