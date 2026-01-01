package io.github.hyungkishin.transentia.api.ui

import io.github.hyungkishin.transentia.api.ui.request.TransferRequest
import io.github.hyungkishin.transentia.api.ui.response.TransferResponse
import io.github.hyungkishin.transentia.application.provided.TransactionRegister
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/transfers")
class TransferController(
    private val registerTransaction: TransactionRegister
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @Valid @RequestBody request: TransferRequest
    ): TransferResponse {
        val result = registerTransaction.createTransfer(request.toCommand())
        return TransferResponse.of(result)
    }

    /**
     * 단건 조회
     */
    @GetMapping("/{transactionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTransfer(@PathVariable transactionId: Long): TransferResponse {
        val res = registerTransaction.findTransfer(transactionId)
        return TransferResponse.of(res)
    }

}

