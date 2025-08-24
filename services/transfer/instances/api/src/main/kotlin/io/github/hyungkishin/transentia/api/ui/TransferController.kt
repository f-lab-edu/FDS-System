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

    /**
     * 송금 요청 생성 (비동기 확정 → PENDING 응답)
     * - 공통 레이어가 ApiCommonResponse<T>로 래핑, POST+PENDING 시 202 설정, Location 자동 추가
     * - Idempotency-Key는 필수(멱등성)
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
//        @RequestHeader("Idempotency-Key")
//        @NotBlank(message = "Idempotency-Key must not be blank")
//        @Size(max = 64, message = "Idempotency-Key must be ≤ 64 chars")
//        idem: String,
        @Valid @RequestBody request: TransferRequest
    ): TransferResponse {
        val result = registerTransaction.create(request.toCommand())
        return TransferResponse.of(result)
    }

    /**
     * 단건 조회
     */
    @GetMapping("/{transactionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(@PathVariable transactionId: Long): TransferResponse {
        val res = registerTransaction.get(transactionId)
        return TransferResponse.of(res)
    }

}

