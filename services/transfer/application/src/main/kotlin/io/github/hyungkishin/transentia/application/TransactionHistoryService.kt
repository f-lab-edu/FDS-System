package io.github.hyungkishin.transentia.application

import io.github.hyungkishin.transentia.application.provided.TransactionHistoryRegister
import io.github.hyungkishin.transentia.application.required.TransactionHistoryRepository
import io.github.hyungkishin.transentia.common.snowflake.IdGenerator
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.model.transaction.Transaction
import io.github.hyungkishin.transentia.container.model.transaction.TransactionHistory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionHistoryService(
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val idGenerator: IdGenerator,
) : TransactionHistoryRegister {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun saveTransferHistory(transaction: Transaction) {
        val history = TransactionHistory.of(transaction, SnowFlakeId(idGenerator.nextId()))
        transactionHistoryRepository.save(history)
    }
}
