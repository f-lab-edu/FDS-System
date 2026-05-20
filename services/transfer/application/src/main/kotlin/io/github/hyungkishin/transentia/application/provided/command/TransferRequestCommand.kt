package io.github.hyungkishin.transentia.application.provided.command

import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.model.Currency

data class TransferRequestCommand(
    val senderAccountNumber: String,
    val receiverAccountNumber: String,
    val amount: String,
    val currency: Currency = Currency.KRW,
    val message: String,
) {
    fun receiverAccountNumber(): String = receiverAccountNumber

    fun amount(): Amount = Amount.parse(amount, currency)
}
