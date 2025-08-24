package io.github.hyungkishin.transentia.infra.config

import io.github.hyungkishin.transentia.application.port.out.AccountBalanceRepository
import io.github.hyungkishin.transentia.shared.snowflake.UserId
import io.github.hyungkishin.transentia.domain.model.AccountBalance
import io.github.hyungkishin.transentia.domain.model.Money
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * FIXME: Auth Domain 생성 이후 삭제
 */
@Component
class MockDataInitializer(
    private val accountBalanceRepository: AccountBalanceRepository
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        if (accountBalanceRepository.findByUserId(UserId(10001)) == null) {

             // = 1000원
             // = 5만원
            val sender = AccountBalance.initialize(UserId(10001), Money.fromDecimalString("10000.00000000"))
            val receiver = AccountBalance.initialize(UserId(20002), Money.fromDecimalString("50000.00000000"))

            accountBalanceRepository.save(sender)
            accountBalanceRepository.save(receiver)

            println("Mock 사용자 금액 초기화")
        }
    }
}