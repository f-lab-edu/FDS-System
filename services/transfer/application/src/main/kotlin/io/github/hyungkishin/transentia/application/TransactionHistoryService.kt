package io.github.hyungkishin.transentia.application

import io.github.hyungkishin.transentia.application.provided.TransactionHistoryRegister
import io.github.hyungkishin.transentia.application.required.TransactionHistoryRepository
import io.github.hyungkishin.transentia.common.snowflake.IdGenerator
import io.github.hyungkishin.transentia.common.snowflake.TransactionId
import io.github.hyungkishin.transentia.consumer.model.Transaction
import io.github.hyungkishin.transentia.consumer.model.TransactionHistory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionHistoryService(
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val idGenerator: IdGenerator,
) : TransactionHistoryRegister {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordSuccess(transaction: Transaction) {
        val history = TransactionHistory.Companion.successOf(transaction, TransactionId(idGenerator.nextId()))
        transactionHistoryRepository.save(history)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordFail(transaction: Transaction, reason: String?) {
        val history = TransactionHistory.Companion.failOf(transaction, TransactionId(idGenerator.nextId()), reason)
        transactionHistoryRepository.save(history)
    }

}