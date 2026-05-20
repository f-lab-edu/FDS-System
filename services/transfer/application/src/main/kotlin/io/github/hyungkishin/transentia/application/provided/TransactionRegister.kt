package io.github.hyungkishin.transentia.application.provided

import io.github.hyungkishin.transentia.application.provided.command.TransferRequestCommand
import io.github.hyungkishin.transentia.application.required.command.TransferResponseCommand

interface TransactionRegister {
    fun createTransfer(command: TransferRequestCommand): TransferResponseCommand

    fun findTransfer(transactionId: Long): TransferResponseCommand
}
