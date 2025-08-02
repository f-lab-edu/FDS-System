package io.github.hyungkishin.transentia.api.ui.request

import io.github.hyungkishin.transentia.application.port.`in`.TransferRequestCommand
import jakarta.validation.constraints.Min
import org.jetbrains.annotations.NotNull

data class TransferRequest(
    @field:NotNull val senderUserId: Long,
    @field:NotNull val receiverUserId: Long,
    @field:Min(1) val amount: String
) {
    fun toCommand(): TransferRequestCommand =
        TransferRequestCommand(senderUserId, receiverUserId, amount)
}