package io.github.hyungkishin.transentia.consumer.event

import io.github.hyungkishin.transentia.common.snowflake.TransferId

data class TransferFailedEvent(
    val transactionId: TransferId,
    val reason: String,
) : TransferEvent