package io.github.hyungkishin.transentia.api.ui.request

import io.github.hyungkishin.transentia.application.provided.command.TransferRequestCommand
import io.github.hyungkishin.transentia.common.model.Currency
import jakarta.validation.constraints.Min
import org.jetbrains.annotations.NotNull

data class TransferRequest(
    @field:NotNull val senderAccountNumber: String,
    @field:NotNull val receiverAccountNumber: String,
    @field:Min(1) val amount: String,
    @field:NotNull val message: String,
    @field:NotNull val currency: Currency,
) {
    fun toCommand(): TransferRequestCommand =
        TransferRequestCommand(senderAccountNumber, receiverAccountNumber, amount, currency, message)
}