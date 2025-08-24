package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.consumer.model.TransactionHistory

interface TransactionHistoryRepository {

    fun save(transactionHistory: TransactionHistory)

}