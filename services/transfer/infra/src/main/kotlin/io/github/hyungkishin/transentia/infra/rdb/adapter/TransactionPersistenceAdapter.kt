package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.port.out.TransactionRepository
import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.domain.model.Transaction
import io.github.hyungkishin.transentia.infra.rdb.entity.TransactionJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.TransactionJpaRepository
import org.springframework.stereotype.Component

@Component
class TransactionPersistenceAdapter(
    private val jpaRepository: TransactionJpaRepository
) : TransactionRepository {

    override fun findById(id: TransferId): Transaction? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun save(transaction: Transaction): Transaction =
        jpaRepository.save(TransactionJpaEntity.from(transaction)).toDomain()
    
}