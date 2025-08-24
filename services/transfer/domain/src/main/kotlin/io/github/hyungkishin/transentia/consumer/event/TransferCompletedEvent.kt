package io.github.hyungkishin.transentia.consumer.event

import io.github.hyungkishin.transentia.common.snowflake.TransferId
import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.model.Money
import java.time.LocalDateTime

data class TransferCompletedEvent(
    val transactionId: TransferId,
    val senderUserId: UserId,
    val receiverUserId: UserId,
    val amount: Money,
) : TransferEvent