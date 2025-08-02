package io.github.hyungkishin.transentia.application.port.out

import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.domain.model.Transaction

interface TransactionRepository {
    fun save(transaction: Transaction): Transaction
    fun findById(id: TransferId): Transaction?
}