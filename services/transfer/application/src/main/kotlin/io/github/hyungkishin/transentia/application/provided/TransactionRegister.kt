package io.github.hyungkishin.transentia.application.provided

import io.github.hyungkishin.transentia.application.provided.command.TransferRequestCommand
import io.github.hyungkishin.transentia.application.required.command.TransferResponseCommand

interface TransactionRegister {
    fun create(command: TransferRequestCommand): TransferResponseCommand

    fun get(transactionId: Long): TransferResponseCommand
}