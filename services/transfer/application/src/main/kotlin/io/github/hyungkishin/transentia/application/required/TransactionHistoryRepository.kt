package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.container.model.transaction.TransactionHistory

interface TransactionHistoryRepository {
    fun save(transactionHistory: TransactionHistory)
}
