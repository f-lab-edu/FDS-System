package io.github.hyungkishin.transentia.api.ui

import io.github.hyungkishin.transentia.api.ui.request.TransferRequest
import io.github.hyungkishin.transentia.api.ui.response.TransferResponse
import io.github.hyungkishin.transentia.application.port.`in`.TransferUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/transfers")
class TransferController(
    private val transferUseCase: TransferUseCase
) {

    @PostMapping
    fun requestTransfer(
        @Valid @RequestBody request: TransferRequest
    ): ResponseEntity<TransferResponse> {
        val result = transferUseCase.requestTransfer(request.toCommand())

        return ResponseEntity.ok(
            TransferResponse(
                transactionId = result.transactionId,
                status = result.status,
                createdAt = result.createdAt,
                receivedAt = result.receivedAt,
            )
        )
    }
}