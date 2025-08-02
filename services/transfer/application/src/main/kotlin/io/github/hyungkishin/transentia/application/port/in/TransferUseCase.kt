package io.github.hyungkishin.transentia.application.port.`in`

import io.github.hyungkishin.transentia.application.port.out.TransferResponseCommand

interface TransferUseCase {
    fun requestTransfer(command: TransferRequestCommand): TransferResponseCommand
}