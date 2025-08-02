package io.github.hyungkishin.transentia.domain.model

import io.github.hyungkishin.transentia.common.snowflake.UserId

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

    fun withdraw(amount: Money) {
        require(balance >= amount) { "사용자 $userId 의 잔액이 부족합니다." }
        balance = balance.subtractOrThrow(amount)
    }

    fun current(): Money = balance
    
}