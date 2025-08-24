package io.github.hyungkishin.transentia.api.ui

import io.github.hyungkishin.transentia.api.ui.request.TransferRequest
import io.github.hyungkishin.transentia.api.ui.response.TransferResponse
import io.github.hyungkishin.transentia.application.port.`in`.TransferService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/transfers")
class TransferController(
    private val transferService: TransferService
) {

    @PostMapping
    fun requestTransfer(
        @Valid @RequestBody request: TransferRequest
    ): TransferResponse {
        val result = transferService.createTransfer(request.toCommand())

        return TransferResponse.of(
            transactionId = result.transactionId,
            status = result.status,
            createdAt = result.createdAt,
            receivedAt = result.receivedAt,
        )
    }
}