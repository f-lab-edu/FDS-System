package io.github.hyungkishin.transentia.application.port.`in`

import io.github.hyungkishin.transentia.application.port.out.TransferResponseCommand

interface TransferService {
    fun createTransfer(command: TransferRequestCommand): TransferResponseCommand
}