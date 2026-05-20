package io.github.hyungkishin.transentia.application.provided

import io.github.hyungkishin.transentia.container.model.transaction.Transaction

interface TransactionHistoryRegister {
    fun saveTransferHistory(transaction: Transaction)
}
