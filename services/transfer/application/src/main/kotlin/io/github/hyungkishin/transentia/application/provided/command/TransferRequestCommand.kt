package io.github.hyungkishin.transentia.application.provided.command

import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.model.Money

data class TransferRequestCommand(
    val senderId: Long,
    val receiverId: Long,
    val amount: String,
) {

    fun senderUserId(): UserId = UserId(senderId)
    fun receiverUserId(): UserId = UserId(receiverId)
    fun amount(): Money = Money.Companion.fromDecimalString(amount)
}