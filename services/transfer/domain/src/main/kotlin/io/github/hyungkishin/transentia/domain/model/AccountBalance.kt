package io.github.hyungkishin.transentia.domain.model

import io.github.hyungkishin.transentia.shared.error.CommonError
import io.github.hyungkishin.transentia.shared.error.DomainException
import io.github.hyungkishin.transentia.shared.snowflake.UserId

class AccountBalance private constructor(
    val userId: UserId,
    var balance: Money,
) {

    companion object {
        fun initialize(userId: UserId, balance: Money): AccountBalance {
            return AccountBalance(userId, balance)
        }
    }

    fun deposit(amount: Money) {
        balance = balance.add(amount)
    }

    /** 출금, 실패 시 도메인 예외 */
    fun withdrawOrThrow(amount: Money) {
        if (balance < amount) {
            throw DomainException(
                CommonError.InvalidArgument(field = "amount", reason = "insufficient_balance"),
                detail = "잔액이 부족합니다. 현재잔액=$balance, 요청금액=$amount"
            )
        }
        balance = balance.subtract(amount)
    }

    fun current(): Money = balance
    
}