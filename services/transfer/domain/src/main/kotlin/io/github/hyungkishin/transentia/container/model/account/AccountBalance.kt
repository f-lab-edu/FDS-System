package io.github.hyungkishin.transentia.container.model.account

import io.github.hyungkishin.transentia.common.error.CommonError
import io.github.hyungkishin.transentia.common.error.DomainException
import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId

class AccountBalance private constructor(
    val id: SnowFlakeId,
    val userId: SnowFlakeId,
    val accountNumber: String,
    var balance: Amount,
) {

    companion object {
        fun of(
            id: SnowFlakeId,
            userId: SnowFlakeId,
            accountNumber: String,
            balance: Amount,
        ): AccountBalance {
            return AccountBalance(id, userId, accountNumber, balance)
        }
    }

    fun deposit(amount: Amount) {
        requireSameCurrency(amount)
        balance = balance.add(amount)
    }

    /** 출금, 실패 시 도메인 예외 */
    fun withdrawOrThrow(amount: Amount) {
        requireSameCurrency(amount)
        if (balance < amount) {
            throw DomainException(
                CommonError.InvalidArgument(field = "amount", reason = "insufficient_balance"),
                detail = "잔액이 부족합니다. 현재잔액=$balance, 요청금액=$amount"
            )
        }
        balance = balance.subtract(amount)
    }

    fun current(): Amount = balance

    fun hasEnoughBalance(amount: Amount): Boolean {
        requireSameCurrency(amount)
        return balance >= amount
    }

    private fun requireSameCurrency(other: Amount) {
        if (balance.currency != other.currency) {
            throw DomainException(
                CommonError.InvalidArgument("currency_mismatch"),
                "계좌 통화(${balance.currency})와 요청 금액 통화(${other.currency})가 다릅니다"
            )
        }
    }
}
