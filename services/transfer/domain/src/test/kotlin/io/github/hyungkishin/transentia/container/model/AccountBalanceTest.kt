//package io.github.hyungkishin.transentia.container.model
//
//import io.github.hyungkishin.transentia.common.error.DomainException
//import io.github.hyungkishin.transentia.common.model.Amount
//import io.github.hyungkishin.transentia.common.model.Currency
//import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
//import io.github.hyungkishin.transentia.container.model.account.AccountBalance
//import org.junit.jupiter.api.Assertions.assertEquals
//import org.junit.jupiter.api.Assertions.assertThrows
//import org.junit.jupiter.api.Test
//
//class AccountBalanceTest {
//
//    private val id = SnowFlakeId(1L)
//    private val userId = SnowFlakeId(2L)
//    private val accountNumber = "110-1234-1234-1234"
//    private val initial = Amount.parse("100.00", Currency.KRW)
//    private val account = AccountBalance.of(id, userId, accountNumber, initial, 1L)
//
//    @Test
//    fun `초기 잔액은 정확히 설정된다`() {
//        assertEquals(initial, account.current())
//    }
//
//    @Test
//    fun `입금 시 잔액이 증가한다`() {
//        account.deposit(Amount.parse("50.00", Currency.KRW))
//        assertEquals(Amount.parse("150.00", Currency.KRW), account.current())
//    }
//
//    @Test
//    fun `출금 시 잔액이 감소한다`() {
//        account.withdrawOrThrow(Amount.parse("30.00", Currency.KRW))
//        assertEquals(Amount.parse("70.00", Currency.KRW), account.current())
//    }
//
//    @Test
//    fun `잔액보다 많은 금액을 출금하면 예외가 발생한다`() {
//        val exception = assertThrows(DomainException::class.java) {
//            account.withdrawOrThrow(Amount.parse("1000.00", Currency.KRW))
//        }
//        println(exception.message)
//    }
//
//    @Test
//    fun `여러 번 입출금해도 잔액 정합성이 유지된다`() {
//        val acc = AccountBalance.of(id, userId, accountNumber, Amount.parse("200.00", Currency.KRW), 1L)
//        acc.withdrawOrThrow(Amount.parse("50.00", Currency.KRW))
//        acc.deposit(Amount.parse("80.00", Currency.KRW))
//        acc.withdrawOrThrow(Amount.parse("30.00", Currency.KRW))
//        assertEquals(Amount.parse("200.00", Currency.KRW), acc.current())
//    }
//
//}