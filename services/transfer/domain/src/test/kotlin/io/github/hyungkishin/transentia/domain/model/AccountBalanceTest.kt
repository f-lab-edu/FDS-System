package io.github.hyungkishin.transentia.domain.model

import io.github.hyungkishin.transentia.shared.error.DomainException
import io.github.hyungkishin.transentia.shared.snowflake.UserId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AccountBalanceTest {

    private val userId = UserId(1L)
    private val initial = Money.fromDecimalString("100.00")
    private val account = AccountBalance.initialize(userId, initial)

    @Test
    fun `초기 잔액은 정확히 설정된다`() {
        assertEquals(initial, account.current())
    }

    @Test
    fun `입금 시 잔액이 증가한다`() {
        account.deposit(Money.fromDecimalString("50.00"))
        assertEquals(Money.fromDecimalString("150.00"), account.current())
    }

    @Test
    fun `출금 시 잔액이 감소한다`() {
        account.withdrawOrThrow(Money.fromDecimalString("30.00"))
        assertEquals(Money.fromDecimalString("70.00"), account.current())
    }

    @Test
    fun `잔액보다 많은 금액을 출금하면 예외가 발생한다`() {
        val exception = assertThrows(DomainException::class.java) {
            account.withdrawOrThrow(Money.fromDecimalString("1000.00"))
        }
        println(exception.message)
    }

    @Test
    fun `여러 번 입출금해도 잔액 정합성이 유지된다`() {
        val acc = AccountBalance.initialize(userId, Money.fromDecimalString("200.00"))
        acc.withdrawOrThrow(Money.fromDecimalString("50.00"))
        acc.deposit(Money.fromDecimalString("80.00"))
        acc.withdrawOrThrow(Money.fromDecimalString("30.00"))
        assertEquals(Money.fromDecimalString("200.00"), acc.current())
    }

}