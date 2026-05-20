package io.github.hyungkishin.transentia.container.validator.transfer

import io.github.hyungkishin.transentia.common.error.CommonError
import io.github.hyungkishin.transentia.common.error.DomainException
import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.container.model.user.User

/**
 * 송금 전체 검증.
 *
 * if/throw 분기는 도메인 객체(User)가 책임지고, 이 validator 는 호출 순서만 잡는다.
 * - 송금 금액 양수 여부
 * - 송신자 assertCanSend (블랙리스트/통화/한도/잔액)
 * - 수신자 assertCanReceive
 */
object TransferValidator {
    fun validate(
        sender: User,
        receiver: User,
        amount: Amount,
        dailyAccumulated: Long = 0L,
    ) {
        assertAmountPositive(amount)
        sender.assertCanSend(amount, dailyAccumulated)
        receiver.assertCanReceive()
    }

    /** 하위 호환 — 신규 호출은 validate() 또는 sender.assertCanSend() 권장. */
    @Deprecated("Use validate() or sender.assertCanSend()")
    fun validateSender(
        sender: User,
        amount: Amount,
        dailyAccumulated: Long = 0L,
    ) = sender.assertCanSend(amount, dailyAccumulated)

    @Deprecated("Use validate() or receiver.assertCanReceive()")
    fun validateReceiver(receiver: User) = receiver.assertCanReceive()

    @Deprecated("Use assertAmountPositive()", ReplaceWith("assertAmountPositive(amount)"))
    fun validateAmount(amount: Amount) = assertAmountPositive(amount)

    private fun assertAmountPositive(amount: Amount) {
        if (!amount.money.isPositive()) {
            throw DomainException(
                CommonError.InvalidArgument("invalid_amount"),
                "송금 금액은 0보다 커야 합니다: $amount",
            )
        }
    }
}
